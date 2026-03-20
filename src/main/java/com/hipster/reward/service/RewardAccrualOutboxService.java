package com.hipster.reward.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.domain.RewardAccrualOutboxStatus;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.repository.RewardAccrualOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RewardAccrualOutboxService {

    @Value("${hipster.reward.default-campaign-code:catalog_bootstrap_v1}")
    private String defaultCampaignCode;

    @Value("${hipster.reward.outbox.batch-size:50}")
    private int outboxBatchSize;

    @Value("${hipster.reward.outbox.retry-delay-ms:30000}")
    private long retryDelayMillis;

    @Value("${hipster.reward.outbox.dispatched-timeout-ms:30000}")
    private long dispatchedTimeoutMillis;

    private final RewardAccrualOutboxRepository rewardAccrualOutboxRepository;
    private final RewardMetricsRecorder rewardMetricsRecorder;

    @Transactional
    public RewardAccrualOutbox enqueueApprovedContribution(final ModerationQueue approvedItem) {
        validateApprovalInput(approvedItem);

        final RewardAccrualOutbox existingOutbox = rewardAccrualOutboxRepository
                .findByApprovalIdAndCampaignCode(approvedItem.getId(), defaultCampaignCode)
                .orElse(null);
        if (existingOutbox != null) {
            rewardMetricsRecorder.recordOutboxCreated("duplicate_ignored");
            return existingOutbox;
        }

        final RewardAccrualOutbox outbox = rewardAccrualOutboxRepository.save(
                RewardAccrualOutbox.pending(approvedItem.getId(), defaultCampaignCode)
        );
        rewardMetricsRecorder.recordOutboxCreated("created");
        return outbox;
    }

    @Transactional(readOnly = true)
    public List<RewardAccrualOutbox> findReadyToDispatch() {
        return rewardAccrualOutboxRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                List.of(RewardAccrualOutboxStatus.PENDING, RewardAccrualOutboxStatus.FAILED),
                LocalDateTime.now(),
                PageRequest.of(0, outboxBatchSize)
        );
    }

    @Transactional
    public boolean tryMarkDispatched(final Long outboxId) {
        final LocalDateTime now = LocalDateTime.now();
        return rewardAccrualOutboxRepository.updateStatusForDispatch(
                outboxId,
                List.of(RewardAccrualOutboxStatus.PENDING, RewardAccrualOutboxStatus.FAILED),
                RewardAccrualOutboxStatus.DISPATCHED,
                now,
                now,
                now,
                null
        ) == 1;
    }

    @Transactional
    public void markProcessed(final Long outboxId) {
        final RewardAccrualOutbox outbox = findOutboxOrThrow(outboxId);
        outbox.markProcessed();
    }

    @Transactional
    public void markFailed(final Long outboxId, final String errorMessage) {
        final RewardAccrualOutbox outbox = findOutboxOrThrow(outboxId);
        outbox.markFailed(errorMessage, LocalDateTime.now().plusNanos(retryDelayMillis * 1_000_000));
    }

    @Transactional
    public int requeueStaleDispatched() {
        final List<RewardAccrualOutbox> staleOutboxRows = rewardAccrualOutboxRepository
                .findByStatusAndProcessedAtIsNullAndDispatchedAtLessThanEqualOrderByDispatchedAtAsc(
                        RewardAccrualOutboxStatus.DISPATCHED,
                        LocalDateTime.now().minusNanos(dispatchedTimeoutMillis * 1_000_000),
                        PageRequest.of(0, outboxBatchSize)
                );

        for (final RewardAccrualOutbox outbox : staleOutboxRows) {
            outbox.markReadyForRetry("dispatch_timeout", LocalDateTime.now());
        }
        return staleOutboxRows.size();
    }

    private RewardAccrualOutbox findOutboxOrThrow(final Long outboxId) {
        return rewardAccrualOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REWARD_ACCRUAL_NOT_FOUND));
    }

    private void validateApprovalInput(final ModerationQueue approvedItem) {
        if (approvedItem.getId() == null || approvedItem.getSubmitterId() == null) {
            throw new BadRequestException(ErrorCode.REWARD_APPROVAL_NOT_ELIGIBLE);
        }
        if (approvedItem.getStatus() != ModerationStatus.APPROVED) {
            throw new BadRequestException(ErrorCode.REWARD_APPROVAL_NOT_ELIGIBLE);
        }
    }
}
