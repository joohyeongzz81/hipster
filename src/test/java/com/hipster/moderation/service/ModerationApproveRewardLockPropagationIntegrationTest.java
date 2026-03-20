package com.hipster.moderation.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.chart.repository.ChartElasticsearchRepository;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.repository.ModerationAuditTrailRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.reward.domain.RewardCampaign;
import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.domain.RewardLedgerEntryType;
import com.hipster.reward.repository.RewardCampaignParticipationRepository;
import com.hipster.reward.repository.RewardCampaignRepository;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
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
import java.util.ArrayList;
import java.util.List;
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
@Disabled("Historical X001 sync baseline evidence. Current HEAD uses reward outbox isolation.")
class ModerationApproveRewardLockPropagationIntegrationTest {
    private static final String DEFAULT_CAMPAIGN_CODE = "catalog_bootstrap_v1";
    private static final int MEASUREMENT_RUNS = 5;
    private static final long LOCK_HOLD_MILLIS = 400L;

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
    private PlatformTransactionManager transactionManager;

    private Long queueId;
    private Long moderatorId;
    private Long reviewId;
    private long sequence = 9000L;

    @BeforeEach
    void setUp() {
        moderationAuditTrailRepository.deleteAll();
        rewardCampaignParticipationRepository.deleteAll();
        rewardLedgerEntryRepository.deleteAll();
        rewardCampaignRepository.deleteAll();
        moderationQueueRepository.deleteAll();
        reviewRepository.deleteAll();

        moderatorId = 9001L;
        final Long submitterId = 7001L;

        final Review review = reviewRepository.save(Review.builder()
                .userId(submitterId)
                .releaseId(8001L)
                .content("lock propagation integration test review content that is long enough")
                .isPublished(false)
                .build());
        reviewId = review.getId();

        final ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(EntityType.REVIEW)
                .entityId(reviewId)
                .submitterId(submitterId)
                .metaComment("approve reward lock propagation")
                .priority(2)
                .build();
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));
        queueId = moderationQueueRepository.save(queueItem).getId();

        rewardCampaignRepository.save(RewardCampaign.defaultCampaign(
                DEFAULT_CAMPAIGN_CODE,
                "reward lock propagation test campaign",
                100L,
                100000L
        ));
    }

    @Test
    @DisplayName("reward campaign lock propagates into moderation approve completion time in sync baseline")
    void approveWaitsForRewardCampaignLockInSyncBaseline() throws Exception {
        final CountDownLatch lockAcquired = new CountDownLatch(1);
        final CountDownLatch releaseLock = new CountDownLatch(1);
        final AtomicLong approveWaitedMillis = new AtomicLong();
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
                    moderationQueueService.approve(queueId, moderatorId, "approve under reward lock");
                    return null;
                } finally {
                    approveWaitedMillis.set(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                }
            });

            Thread.sleep(250L);
            assertThat(approveFuture.isDone())
                    .as("approve should stay blocked while reward campaign lock is held in sync baseline")
                    .isFalse();

            releaseLock.countDown();
            approveFuture.get(5, TimeUnit.SECONDS);

            final ModerationQueue approvedQueue = moderationQueueRepository.findById(queueId).orElseThrow();
            final Review publishedReview = reviewRepository.findById(reviewId).orElseThrow();
            final RewardLedgerEntry accrualEntry = rewardLedgerEntryRepository
                    .findByApprovalIdAndCampaignCodeAndEntryType(queueId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL)
                    .orElseThrow();

            assertThat(approvedQueue.getStatus()).isEqualTo(ModerationStatus.APPROVED);
            assertThat(publishedReview.getIsPublished()).isTrue();
            assertThat(accrualEntry.getApprovalId()).isEqualTo(queueId);
            assertThat(accrualEntry.getPointsDelta()).isEqualTo(100L);
            assertThat(approveWaitedMillis.get())
                    .as("approve duration should include reward campaign lock wait in sync baseline")
                    .isGreaterThanOrEqualTo(200L);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("approve latency shows a clear delta between no-lock baseline and reward-lock contention")
    void approveLatencyShowsClearDeltaBetweenBaselineAndRewardLockContention() throws Exception {
        final List<Long> noLockDurations = new ArrayList<>();
        final List<Long> lockDurations = new ArrayList<>();

        for (int index = 0; index < MEASUREMENT_RUNS; index++) {
            final ApprovalContext noLockContext = createApprovalContext();
            noLockDurations.add(measureApproveDuration(noLockContext.queueId(), noLockContext.moderatorId()));
        }

        for (int index = 0; index < MEASUREMENT_RUNS; index++) {
            final ApprovalContext lockContext = createApprovalContext();
            lockDurations.add(measureApproveDurationUnderCampaignLock(lockContext.queueId(), lockContext.moderatorId()));
        }

        final DurationStats noLockStats = DurationStats.from(noLockDurations);
        final DurationStats lockStats = DurationStats.from(lockDurations);

        System.out.printf(
                "SYNC_APPROVE_BASELINE noLock avg=%dms min=%dms max=%dms | withLock avg=%dms min=%dms max=%dms%n",
                noLockStats.averageMillis(),
                noLockStats.minMillis(),
                noLockStats.maxMillis(),
                lockStats.averageMillis(),
                lockStats.minMillis(),
                lockStats.maxMillis()
        );

        assertThat(lockStats.averageMillis())
                .as("sync approve average latency should clearly increase when reward campaign lock is held")
                .isGreaterThan(noLockStats.averageMillis() + 250L);
        assertThat(lockStats.minMillis())
                .as("even the fastest lock-contention run should still pay meaningful lock wait")
                .isGreaterThanOrEqualTo(LOCK_HOLD_MILLIS - 50L);
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

    private ApprovalContext createApprovalContext() {
        final long submitterId = ++sequence;
        final long releaseId = ++sequence;

        final Review review = reviewRepository.save(Review.builder()
                .userId(submitterId)
                .releaseId(releaseId)
                .content("approve latency measurement review content that is long enough")
                .isPublished(false)
                .build());

        final ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(EntityType.REVIEW)
                .entityId(review.getId())
                .submitterId(submitterId)
                .metaComment("approve latency measurement")
                .priority(2)
                .build();
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        final ModerationQueue savedQueue = moderationQueueRepository.save(queueItem);
        return new ApprovalContext(savedQueue.getId(), moderatorId);
    }

    private long measureApproveDuration(final Long approvalQueueId, final Long actingModeratorId) {
        final long startedAt = System.nanoTime();
        moderationQueueService.approve(approvalQueueId, actingModeratorId, "approve without reward lock");
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private long measureApproveDurationUnderCampaignLock(final Long approvalQueueId,
                                                         final Long actingModeratorId) throws Exception {
        final CountDownLatch lockAcquired = new CountDownLatch(1);
        final CountDownLatch releaseLock = new CountDownLatch(1);
        final AtomicLong waitedMillis = new AtomicLong();
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
                    moderationQueueService.approve(approvalQueueId, actingModeratorId, "approve with reward lock");
                    return null;
                } finally {
                    waitedMillis.set(Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                }
            });

            Thread.sleep(LOCK_HOLD_MILLIS);
            releaseLock.countDown();
            approveFuture.get(5, TimeUnit.SECONDS);
            return waitedMillis.get();
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private record ApprovalContext(Long queueId, Long moderatorId) {
    }

    private record DurationStats(long averageMillis, long minMillis, long maxMillis) {
        private static DurationStats from(final List<Long> samples) {
            long total = 0L;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;

            for (final long sample : samples) {
                total += sample;
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }

            return new DurationStats(total / samples.size(), min, max);
        }
    }
}
