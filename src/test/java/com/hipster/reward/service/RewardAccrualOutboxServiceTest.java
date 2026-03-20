package com.hipster.reward.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.domain.RewardAccrualOutboxStatus;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.repository.RewardAccrualOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RewardAccrualOutboxServiceTest {

    private static final String DEFAULT_CAMPAIGN_CODE = "catalog_bootstrap_v1";

    @InjectMocks
    private RewardAccrualOutboxService rewardAccrualOutboxService;

    @Mock
    private RewardAccrualOutboxRepository rewardAccrualOutboxRepository;

    @Mock
    private RewardMetricsRecorder rewardMetricsRecorder;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rewardAccrualOutboxService, "defaultCampaignCode", DEFAULT_CAMPAIGN_CODE);
        ReflectionTestUtils.setField(rewardAccrualOutboxService, "outboxBatchSize", 50);
        ReflectionTestUtils.setField(rewardAccrualOutboxService, "retryDelayMillis", 30_000L);
        ReflectionTestUtils.setField(rewardAccrualOutboxService, "dispatchedTimeoutMillis", 30_000L);
    }

    @Test
    @DisplayName("approved moderation item creates a pending accrual outbox entry")
    void enqueueApprovedContribution_CreatesPendingOutbox() {
        final ModerationQueue approvedItem = approvedQueue(11L, 101L);
        final RewardAccrualOutbox savedOutbox = RewardAccrualOutbox.pending(approvedItem.getId(), DEFAULT_CAMPAIGN_CODE);

        given(rewardAccrualOutboxRepository.findByApprovalIdAndCampaignCode(approvedItem.getId(), DEFAULT_CAMPAIGN_CODE))
                .willReturn(Optional.empty());
        given(rewardAccrualOutboxRepository.save(any(RewardAccrualOutbox.class))).willReturn(savedOutbox);

        final RewardAccrualOutbox outbox = rewardAccrualOutboxService.enqueueApprovedContribution(approvedItem);

        assertThat(outbox.getApprovalId()).isEqualTo(approvedItem.getId());
        assertThat(outbox.getCampaignCode()).isEqualTo(DEFAULT_CAMPAIGN_CODE);
        assertThat(outbox.getStatus()).isEqualTo(RewardAccrualOutboxStatus.PENDING);
        verify(rewardMetricsRecorder).recordOutboxCreated("created");
    }

    @Test
    @DisplayName("duplicate approved moderation item reuses existing outbox entry")
    void enqueueApprovedContribution_ReusesExistingOutbox() {
        final ModerationQueue approvedItem = approvedQueue(12L, 102L);
        final RewardAccrualOutbox existingOutbox = RewardAccrualOutbox.pending(approvedItem.getId(), DEFAULT_CAMPAIGN_CODE);

        given(rewardAccrualOutboxRepository.findByApprovalIdAndCampaignCode(approvedItem.getId(), DEFAULT_CAMPAIGN_CODE))
                .willReturn(Optional.of(existingOutbox));

        final RewardAccrualOutbox outbox = rewardAccrualOutboxService.enqueueApprovedContribution(approvedItem);

        assertThat(outbox).isSameAs(existingOutbox);
        verify(rewardMetricsRecorder).recordOutboxCreated("duplicate_ignored");
    }

    @Test
    @DisplayName("ready to dispatch returns pending and failed outbox rows ordered by creation")
    void findReadyToDispatch_ReturnsPendingAndFailedRows() {
        final RewardAccrualOutbox pendingOutbox = RewardAccrualOutbox.pending(21L, DEFAULT_CAMPAIGN_CODE);
        final RewardAccrualOutbox failedOutbox = RewardAccrualOutbox.pending(22L, DEFAULT_CAMPAIGN_CODE);
        failedOutbox.markFailed("publish_failed", LocalDateTime.now());

        given(rewardAccrualOutboxRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                any(), any(), any()
        )).willReturn(List.of(pendingOutbox, failedOutbox));

        final List<RewardAccrualOutbox> readyOutbox = rewardAccrualOutboxService.findReadyToDispatch();

        assertThat(readyOutbox).containsExactly(pendingOutbox, failedOutbox);
    }

    @Test
    @DisplayName("dispatch claim is granted once for ready outbox rows")
    void tryMarkDispatched_ReturnsTrueWhenClaimSucceeds() {
        given(rewardAccrualOutboxRepository.updateStatusForDispatch(
                any(), anyList(), any(), any(), any(), any(), any()
        )).willReturn(1);

        final boolean claimed = rewardAccrualOutboxService.tryMarkDispatched(41L);

        assertThat(claimed).isTrue();
    }

    @Test
    @DisplayName("stale dispatched rows are requeued as failed retry candidates")
    void requeueStaleDispatched_RequeuesRows() {
        final RewardAccrualOutbox staleOutbox = RewardAccrualOutbox.pending(51L, DEFAULT_CAMPAIGN_CODE);
        staleOutbox.markDispatched();

        given(rewardAccrualOutboxRepository.findByStatusAndProcessedAtIsNullAndDispatchedAtLessThanEqualOrderByDispatchedAtAsc(
                any(), any(), any()
        )).willReturn(List.of(staleOutbox));

        final int requeuedCount = rewardAccrualOutboxService.requeueStaleDispatched();

        assertThat(requeuedCount).isEqualTo(1);
        assertThat(staleOutbox.getStatus()).isEqualTo(RewardAccrualOutboxStatus.FAILED);
        assertThat(staleOutbox.getLastError()).isEqualTo("dispatch_timeout");
    }

    @Test
    @DisplayName("non-approved moderation item is rejected for outbox enqueue")
    void enqueueApprovedContribution_RejectsNonApprovedItem() {
        final ModerationQueue pendingItem = ModerationQueue.builder()
                .entityType(EntityType.REVIEW)
                .entityId(301L)
                .submitterId(401L)
                .metaComment("pending item")
                .priority(2)
                .build();
        ReflectionTestUtils.setField(pendingItem, "id", 31L);

        assertThatThrownBy(() -> rewardAccrualOutboxService.enqueueApprovedContribution(pendingItem))
                .isInstanceOf(BadRequestException.class);
    }

    private ModerationQueue approvedQueue(final Long queueId, final Long submitterId) {
        final ModerationQueue approvedItem = ModerationQueue.builder()
                .entityType(EntityType.REVIEW)
                .entityId(queueId + 1000L)
                .submitterId(submitterId)
                .metaComment("approved outbox")
                .priority(2)
                .build();
        ReflectionTestUtils.setField(approvedItem, "id", queueId);
        approvedItem.approve("approved");
        return approvedItem;
    }
}
