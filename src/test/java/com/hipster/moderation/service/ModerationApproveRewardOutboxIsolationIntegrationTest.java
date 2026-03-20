package com.hipster.moderation.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.chart.repository.ChartElasticsearchRepository;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.repository.ModerationAuditTrailRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.domain.RewardAccrualOutboxStatus;
import com.hipster.reward.domain.RewardCampaign;
import com.hipster.reward.domain.RewardLedgerEntryType;
import com.hipster.reward.repository.RewardAccrualOutboxRepository;
import com.hipster.reward.repository.RewardCampaignParticipationRepository;
import com.hipster.reward.repository.RewardCampaignRepository;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
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
import java.time.LocalDateTime;
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
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hipster.reward.outbox.publisher.enabled=false"
})
class ModerationApproveRewardOutboxIsolationIntegrationTest {

    private static final String DEFAULT_CAMPAIGN_CODE = "catalog_bootstrap_v1";
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
    private ModerationQueueService moderationQueueService;

    @Autowired
    private ModerationQueueRepository moderationQueueRepository;

    @Autowired
    private ModerationAuditTrailRepository moderationAuditTrailRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private RewardCampaignRepository rewardCampaignRepository;

    @Autowired
    private RewardLedgerEntryRepository rewardLedgerEntryRepository;

    @Autowired
    private RewardCampaignParticipationRepository rewardCampaignParticipationRepository;

    @Autowired
    private RewardAccrualOutboxRepository rewardAccrualOutboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long queueId;
    private Long moderatorId;
    private Long reviewId;

    @BeforeEach
    void setUp() {
        moderationAuditTrailRepository.deleteAll();
        rewardCampaignParticipationRepository.deleteAll();
        rewardLedgerEntryRepository.deleteAll();
        rewardAccrualOutboxRepository.deleteAll();
        rewardCampaignRepository.deleteAll();
        moderationQueueRepository.deleteAll();
        reviewRepository.deleteAll();

        moderatorId = 9101L;
        final Long submitterId = 7101L;

        final Review review = reviewRepository.save(Review.builder()
                .userId(submitterId)
                .releaseId(8101L)
                .content("outbox isolation integration test review content that is long enough")
                .isPublished(false)
                .build());
        reviewId = review.getId();

        final ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(EntityType.REVIEW)
                .entityId(reviewId)
                .submitterId(submitterId)
                .metaComment("approve reward outbox isolation")
                .priority(2)
                .build();
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));
        queueId = moderationQueueRepository.save(queueItem).getId();

        rewardCampaignRepository.save(RewardCampaign.defaultCampaign(
                DEFAULT_CAMPAIGN_CODE,
                "reward outbox isolation test campaign",
                100L,
                100000L
        ));
    }

    @Test
    @DisplayName("approve completes under reward campaign lock and leaves a pending outbox entry")
    void approveCompletesUnderRewardCampaignLockAndLeavesPendingOutbox() throws Exception {
        final CountDownLatch lockAcquired = new CountDownLatch(1);
        final CountDownLatch releaseLock = new CountDownLatch(1);
        final AtomicLong approveElapsedMillis = new AtomicLong();
        final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)
                        .orElseThrow();
                lockAcquired.countDown();
                await(releaseLock);
            }));

            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            final Future<Void> approveFuture = executor.submit(() -> {
                final long startedAt = System.nanoTime();
                try {
                    moderationQueueService.approve(queueId, moderatorId, "approve with reward lock but isolated");
                    return null;
                } finally {
                    approveElapsedMillis.set(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                }
            });

            approveFuture.get(250, TimeUnit.MILLISECONDS);

            final ModerationQueue approvedQueue = moderationQueueRepository.findById(queueId).orElseThrow();
            final Review publishedReview = reviewRepository.findById(reviewId).orElseThrow();
            final RewardAccrualOutbox outbox = rewardAccrualOutboxRepository
                    .findByApprovalIdAndCampaignCode(queueId, DEFAULT_CAMPAIGN_CODE)
                    .orElseThrow();

            assertThat(approvedQueue.getStatus()).isEqualTo(ModerationStatus.APPROVED);
            assertThat(publishedReview.getIsPublished()).isTrue();
            assertThat(outbox.getStatus()).isEqualTo(RewardAccrualOutboxStatus.PENDING);
            assertThat(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                    queueId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
            )).isEmpty();
            assertThat(approveElapsedMillis.get())
                    .as("approve should finish without waiting for the reward campaign lock once outbox isolation is in place")
                    .isLessThan(250L);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding reward campaign lock", exception);
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
