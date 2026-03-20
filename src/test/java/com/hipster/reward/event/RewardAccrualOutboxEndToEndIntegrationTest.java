package com.hipster.reward.event;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.chart.repository.ChartElasticsearchRepository;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.global.config.RabbitMqConfig;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.repository.ModerationAuditTrailRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.moderation.service.ModerationQueueService;
import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.domain.RewardAccrualOutboxStatus;
import com.hipster.reward.domain.RewardCampaign;
import com.hipster.reward.domain.RewardLedgerEntry;
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
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hipster.reward.outbox.publish-fixed-delay-ms=600000"
})
class RewardAccrualOutboxEndToEndIntegrationTest {

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
    private RewardAccrualOutboxPublisher rewardAccrualOutboxPublisher;

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
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry endpointRegistry;

    private Long moderatorId;

    @BeforeEach
    void setUp() {
        moderationAuditTrailRepository.deleteAll();
        rewardCampaignParticipationRepository.deleteAll();
        rewardLedgerEntryRepository.deleteAll();
        rewardAccrualOutboxRepository.deleteAll();
        rewardCampaignRepository.deleteAll();
        moderationQueueRepository.deleteAll();
        reviewRepository.deleteAll();

        rabbitAdmin.purgeQueue(RabbitMqConfig.REWARD_ACCRUAL_QUEUE, true);
        final MessageListenerContainer rewardListenerContainer = endpointRegistry.getListenerContainer("rewardAccrualListener");
        if (rewardListenerContainer != null && !rewardListenerContainer.isRunning()) {
            rewardListenerContainer.start();
        }

        moderatorId = 9201L;
        rewardCampaignRepository.save(RewardCampaign.defaultCampaign(
                DEFAULT_CAMPAIGN_CODE,
                "reward outbox end-to-end test campaign",
                100L,
                100000L
        ));
    }

    @Test
    @DisplayName("approve creates outbox and Rabbit publish-consume converges to processed accrual")
    void approvePublishConsumeConvergesToProcessedAccrual() {
        final ApprovalContext approval = createApprovalContext(10001L);

        moderationQueueService.approve(approval.queueId(), moderatorId, "approve to create outbox");

        final RewardAccrualOutbox pendingOutbox = rewardAccrualOutboxRepository
                .findByApprovalIdAndCampaignCode(approval.queueId(), DEFAULT_CAMPAIGN_CODE)
                .orElseThrow();
        assertThat(pendingOutbox.getStatus()).isEqualTo(RewardAccrualOutboxStatus.PENDING);

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        final RewardAccrualOutbox processedOutbox = waitForOutboxStatus(approval.queueId(), RewardAccrualOutboxStatus.PROCESSED);
        final RewardLedgerEntry accrualEntry = waitForAccrualEntry(approval.queueId());

        assertThat(processedOutbox.getProcessedAt()).isNotNull();
        assertThat(accrualEntry.getApprovalId()).isEqualTo(approval.queueId());
        assertThat(accrualEntry.getEntryType()).isEqualTo(RewardLedgerEntryType.ACCRUAL);
        assertThat(accrualEntry.getPointsDelta()).isEqualTo(100L);
    }

    @Test
    @DisplayName("failed outbox row with past retry time is redispatched and converges to processed accrual")
    void failedOutboxRowIsRedispatchedAndProcessed() {
        final ApprovalContext approval = createApprovalContext(10002L);

        moderationQueueService.approve(approval.queueId(), moderatorId, "approve to create failed outbox");

        final RewardAccrualOutbox outbox = rewardAccrualOutboxRepository
                .findByApprovalIdAndCampaignCode(approval.queueId(), DEFAULT_CAMPAIGN_CODE)
                .orElseThrow();
        outbox.markFailed("synthetic_failure", LocalDateTime.now().minusSeconds(1));
        rewardAccrualOutboxRepository.saveAndFlush(outbox);

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        final RewardAccrualOutbox processedOutbox = waitForOutboxStatus(approval.queueId(), RewardAccrualOutboxStatus.PROCESSED);
        final RewardLedgerEntry accrualEntry = waitForAccrualEntry(approval.queueId());

        assertThat(processedOutbox.getPublishAttemptCount()).isGreaterThanOrEqualTo(1);
        assertThat(accrualEntry.getApprovalId()).isEqualTo(approval.queueId());
    }

    @Test
    @DisplayName("stale dispatched outbox row is requeued and converges to processed accrual")
    void staleDispatchedOutboxRowIsRequeuedAndProcessed() {
        final ApprovalContext approval = createApprovalContext(10003L);

        moderationQueueService.approve(approval.queueId(), moderatorId, "approve to create stale dispatched outbox");

        final RewardAccrualOutbox outbox = rewardAccrualOutboxRepository
                .findByApprovalIdAndCampaignCode(approval.queueId(), DEFAULT_CAMPAIGN_CODE)
                .orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(outbox, "status", RewardAccrualOutboxStatus.DISPATCHED);
        org.springframework.test.util.ReflectionTestUtils.setField(outbox, "dispatchedAt", LocalDateTime.now().minusMinutes(1));
        org.springframework.test.util.ReflectionTestUtils.setField(outbox, "nextAttemptAt", LocalDateTime.now());
        rewardAccrualOutboxRepository.saveAndFlush(outbox);

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        final RewardAccrualOutbox processedOutbox = waitForOutboxStatus(approval.queueId(), RewardAccrualOutboxStatus.PROCESSED);
        final RewardLedgerEntry accrualEntry = waitForAccrualEntry(approval.queueId());

        assertThat(processedOutbox.getApprovalId()).isEqualTo(approval.queueId());
        assertThat(accrualEntry.getApprovalId()).isEqualTo(approval.queueId());
    }

    @Test
    @DisplayName("consumer downtime plus stale dispatched recovery still converges to exactly one accrual")
    void consumerDowntimeAndStaleRecoveryStillConvergesToOneAccrual() {
        final ApprovalContext approval = createApprovalContext(10004L);
        final MessageListenerContainer rewardListenerContainer = endpointRegistry.getListenerContainer("rewardAccrualListener");
        assertThat(rewardListenerContainer).isNotNull();

        rewardListenerContainer.stop();
        assertThat(rewardListenerContainer.isRunning()).isFalse();

        moderationQueueService.approve(approval.queueId(), moderatorId, "approve while reward listener is stopped");
        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        final RewardAccrualOutbox dispatchedOutbox = rewardAccrualOutboxRepository
                .findByApprovalIdAndCampaignCode(approval.queueId(), DEFAULT_CAMPAIGN_CODE)
                .orElseThrow();
        assertThat(dispatchedOutbox.getStatus()).isEqualTo(RewardAccrualOutboxStatus.DISPATCHED);
        assertThat(queueMessageCount()).isGreaterThanOrEqualTo(1);

        org.springframework.test.util.ReflectionTestUtils.setField(dispatchedOutbox, "dispatchedAt", LocalDateTime.now().minusMinutes(1));
        rewardAccrualOutboxRepository.saveAndFlush(dispatchedOutbox);
        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        assertThat(queueMessageCount()).isGreaterThanOrEqualTo(2);

        rewardListenerContainer.start();
        final RewardAccrualOutbox processedOutbox = waitForOutboxStatus(approval.queueId(), RewardAccrualOutboxStatus.PROCESSED);
        final RewardLedgerEntry accrualEntry = waitForAccrualEntry(approval.queueId());
        final List<RewardLedgerEntry> approvalEntries = rewardLedgerEntryRepository.findAllByApprovalIdOrderByCreatedAtAsc(approval.queueId());

        assertThat(processedOutbox.getApprovalId()).isEqualTo(approval.queueId());
        assertThat(accrualEntry.getApprovalId()).isEqualTo(approval.queueId());
        assertThat(approvalEntries.stream()
                .filter(entry -> entry.getEntryType() == RewardLedgerEntryType.ACCRUAL)
                .count()).isEqualTo(1L);
    }

    private ApprovalContext createApprovalContext(final Long releaseId) {
        final Long submitterId = releaseId + 1000L;
        final Review review = reviewRepository.save(Review.builder()
                .userId(submitterId)
                .releaseId(releaseId)
                .content("reward outbox e2e review content long enough to pass validation")
                .isPublished(false)
                .build());

        final ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(EntityType.REVIEW)
                .entityId(review.getId())
                .submitterId(submitterId)
                .metaComment("reward outbox e2e")
                .priority(2)
                .build();
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        final ModerationQueue savedQueue = moderationQueueRepository.save(queueItem);
        return new ApprovalContext(savedQueue.getId());
    }

    private RewardAccrualOutbox waitForOutboxStatus(final Long approvalId, final RewardAccrualOutboxStatus expectedStatus) {
        final long deadline = System.currentTimeMillis() + 10_000L;

        while (System.currentTimeMillis() < deadline) {
            final Optional<RewardAccrualOutbox> outbox = rewardAccrualOutboxRepository
                    .findByApprovalIdAndCampaignCode(approvalId, DEFAULT_CAMPAIGN_CODE);
            if (outbox.isPresent() && outbox.get().getStatus() == expectedStatus) {
                return outbox.get();
            }
            sleep(100L);
        }

        final RewardAccrualOutbox currentOutbox = rewardAccrualOutboxRepository
                .findByApprovalIdAndCampaignCode(approvalId, DEFAULT_CAMPAIGN_CODE)
                .orElseThrow();
        throw new AssertionError("Outbox did not reach expected status. currentStatus=" + currentOutbox.getStatus());
    }

    private RewardLedgerEntry waitForAccrualEntry(final Long approvalId) {
        final long deadline = System.currentTimeMillis() + 10_000L;

        while (System.currentTimeMillis() < deadline) {
            final Optional<RewardLedgerEntry> accrualEntry = rewardLedgerEntryRepository
                    .findByApprovalIdAndCampaignCodeAndEntryType(approvalId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL);
            if (accrualEntry.isPresent()) {
                return accrualEntry.get();
            }
            sleep(100L);
        }

        throw new AssertionError("Accrual entry was not created for approvalId=" + approvalId);
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for reward outbox convergence", exception);
        }
    }

    private int queueMessageCount() {
        final java.util.Properties properties = rabbitAdmin.getQueueProperties(RabbitMqConfig.REWARD_ACCRUAL_QUEUE);
        return (Integer) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
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

    private record ApprovalContext(Long queueId) {
    }
}
