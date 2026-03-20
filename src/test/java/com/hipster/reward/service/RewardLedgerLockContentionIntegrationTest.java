package com.hipster.reward.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.chart.repository.ChartElasticsearchRepository;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.reward.domain.RewardCampaign;
import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.repository.RewardCampaignParticipationRepository;
import com.hipster.reward.repository.RewardCampaignRepository;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RewardLedgerLockContentionIntegrationTest {

    private static final String DEFAULT_TEST_JDBC_URL =
            "jdbc:mysql://localhost:3306/hipster_reward_lock_test?createDatabaseIfNotExist=true&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_TEST_USERNAME = "root";
    private static final String DEFAULT_TEST_PASSWORD = "password";

    @MockBean
    private ChartElasticsearchRepository chartElasticsearchRepository;

    @MockBean
    private ChartElasticsearchIndexService chartElasticsearchIndexService;

    @MockBean
    private ElasticsearchClient elasticsearchClient;

    @MockBean
    private ElasticsearchOperations elasticsearchOperations;

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.master.jdbc-url", () -> requiredProperty("reward.lock.test.jdbc-url"));
        registry.add("spring.datasource.master.username", () -> requiredProperty("reward.lock.test.username"));
        registry.add("spring.datasource.master.password", () -> requiredProperty("reward.lock.test.password"));
        registry.add("spring.datasource.master.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.slave.jdbc-url", () -> requiredProperty("reward.lock.test.jdbc-url"));
        registry.add("spring.datasource.slave.username", () -> requiredProperty("reward.lock.test.username"));
        registry.add("spring.datasource.slave.password", () -> requiredProperty("reward.lock.test.password"));
        registry.add("spring.datasource.slave.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private RewardLedgerService rewardLedgerService;

    @Autowired
    private RewardCampaignRepository rewardCampaignRepository;

    @Autowired
    private RewardLedgerEntryRepository rewardLedgerEntryRepository;

    @Autowired
    private RewardCampaignParticipationRepository rewardCampaignParticipationRepository;

    @Autowired
    private ModerationQueueRepository moderationQueueRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long userId;
    private Long firstApprovalId;
    private Long secondApprovalId;

    @BeforeEach
    void setUp() {
        rewardCampaignParticipationRepository.deleteAll();
        rewardLedgerEntryRepository.deleteAll();
        rewardCampaignRepository.deleteAll();
        moderationQueueRepository.deleteAll();
        userRepository.deleteAll();

        final User user = userRepository.save(User.builder()
                .username("reward_lock_user")
                .email("reward-lock@test.com")
                .passwordHash("hash")
                .build());
        userId = user.getId();

        firstApprovalId = moderationQueueRepository.save(approvedQueueItem(userId, 101L)).getId();
        secondApprovalId = moderationQueueRepository.save(approvedQueueItem(userId, 102L)).getId();

        rewardCampaignRepository.save(RewardCampaign.defaultCampaign(
                "catalog_bootstrap_v1",
                "reward lock test campaign",
                100L,
                100000L
        ));
    }

    @Test
    @DisplayName("campaign row lock blocks a different accrual request")
    void accrualWaitsWhileDefaultCampaignRowIsLocked() throws Exception {
        final CountDownLatch lockAcquired = new CountDownLatch(1);
        final CountDownLatch releaseLock = new CountDownLatch(1);
        final AtomicLong waitedMillis = new AtomicLong();
        final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                rewardCampaignRepository.findByCodeForUpdate("catalog_bootstrap_v1")
                        .orElseThrow();
                lockAcquired.countDown();
                await(releaseLock);
            }));

            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            final Future<RewardLedgerEntry> accrualFuture = executor.submit(() -> {
                final long startedAt = System.nanoTime();
                try {
                    return rewardLedgerService.accrueApprovedContribution(secondApprovalId);
                } finally {
                    waitedMillis.set(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                }
            });

            Thread.sleep(250L);
            assertThat(accrualFuture.isDone())
                    .as("a second accrual should remain blocked while the campaign row lock is held")
                    .isFalse();

            releaseLock.countDown();

            final RewardLedgerEntry entry = accrualFuture.get(5, TimeUnit.SECONDS);
            assertThat(entry.getApprovalId()).isEqualTo(secondApprovalId);
            assertThat(entry.getPointsDelta()).isEqualTo(100L);
            assertThat(waitedMillis.get())
                    .as("the second accrual should include actual lock wait time")
                    .isGreaterThanOrEqualTo(200L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("duplicate accrual fast path returns before the campaign row lock")
    void duplicateApprovalFastPathSkipsCampaignLock() throws Exception {
        final RewardLedgerEntry initialEntry = rewardLedgerService.accrueApprovedContribution(firstApprovalId);
        assertThat(initialEntry.getApprovalId()).isEqualTo(firstApprovalId);

        final CountDownLatch lockAcquired = new CountDownLatch(1);
        final CountDownLatch releaseLock = new CountDownLatch(1);
        final AtomicLong waitedMillis = new AtomicLong();
        final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                rewardCampaignRepository.findByCodeForUpdate("catalog_bootstrap_v1")
                        .orElseThrow();
                lockAcquired.countDown();
                await(releaseLock);
            }));

            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            final Future<RewardLedgerEntry> duplicateFuture = executor.submit(() -> {
                final long startedAt = System.nanoTime();
                try {
                    return rewardLedgerService.accrueApprovedContribution(firstApprovalId);
                } finally {
                    waitedMillis.set(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                }
            });

            final RewardLedgerEntry duplicateResult = duplicateFuture.get(500, TimeUnit.MILLISECONDS);
            assertThat(duplicateResult.getId()).isEqualTo(initialEntry.getId());
            assertThat(waitedMillis.get())
                    .as("duplicate approvals should return before the expensive row lock")
                    .isLessThan(200L);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private ModerationQueue approvedQueueItem(final Long submitterId, final Long entityId) {
        final ModerationQueue item = ModerationQueue.builder()
                .entityType(EntityType.RELEASE)
                .entityId(entityId)
                .submitterId(submitterId)
                .metaComment("reward lock contention test")
                .priority(2)
                .build();
        item.approve("approved for reward lock test");
        return item;
    }

    private void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding campaign lock", exception);
        }
    }

    private static String requiredProperty(final String key) {
        final String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        final String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        final String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        return switch (key) {
            case "reward.lock.test.jdbc-url" -> DEFAULT_TEST_JDBC_URL;
            case "reward.lock.test.username" -> DEFAULT_TEST_USERNAME;
            case "reward.lock.test.password" -> DEFAULT_TEST_PASSWORD;
            default -> throw new IllegalStateException("Missing required property or env: " + key + " / " + envKey);
        };
    }
}
