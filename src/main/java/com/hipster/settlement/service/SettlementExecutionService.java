package com.hipster.settlement.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementPayoutOutbox;
import com.hipster.settlement.domain.SettlementPayoutOutboxStatus;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.gateway.PayoutGateway;
import com.hipster.settlement.gateway.PayoutGatewayResult;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementPayoutOutboxRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementExecutionService {

    private static final List<SettlementPayoutOutboxStatus> DISPATCHABLE_STATUSES = List.of(
            SettlementPayoutOutboxStatus.PENDING,
            SettlementPayoutOutboxStatus.FAILED
    );

    private final SettlementPayoutOutboxRepository settlementPayoutOutboxRepository;
    private final SettlementRequestRepository settlementRequestRepository;
    private final SettlementAllocationRepository settlementAllocationRepository;
    private final PayoutGateway payoutGateway;

    @Value("${hipster.settlement.outbox.batch-size:50}")
    private int batchSize;

    @Value("${hipster.settlement.outbox.retry-delay-ms:30000}")
    private long retryDelayMs;

    @Transactional
    public void dispatchPendingRequests() {
        final LocalDateTime now = LocalDateTime.now();
        final List<SettlementPayoutOutbox> candidates =
                settlementPayoutOutboxRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        DISPATCHABLE_STATUSES,
                        now,
                        PageRequest.of(0, Math.max(batchSize, 1))
                );

        for (SettlementPayoutOutbox candidate : candidates) {
            dispatchOutbox(candidate.getId(), now);
        }
    }

    private void dispatchOutbox(final Long outboxId, final LocalDateTime referenceTime) {
        final int claimed = settlementPayoutOutboxRepository.updateStatusForDispatch(
                outboxId,
                DISPATCHABLE_STATUSES,
                SettlementPayoutOutboxStatus.DISPATCHED,
                referenceTime,
                referenceTime,
                referenceTime,
                null
        );
        if (claimed == 0) {
            return;
        }

        final SettlementPayoutOutbox outbox = settlementPayoutOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SETTLEMENT_REQUEST_NOT_FOUND));
        final SettlementRequest settlementRequest = settlementRequestRepository.findById(outbox.getSettlementRequestId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.SETTLEMENT_REQUEST_NOT_FOUND));

        try {
            final PayoutGatewayResult result = payoutGateway.execute(settlementRequest);
            applyGatewayResult(settlementRequest, outbox, result);
        } catch (RuntimeException exception) {
            outbox.markFailed(exception.getMessage(), referenceTime.plusNanos(retryDelayMs * 1_000_000));
        }
    }

    private void applyGatewayResult(final SettlementRequest settlementRequest,
                                    final SettlementPayoutOutbox outbox,
                                    final PayoutGatewayResult result) {
        if (result.succeeded()) {
            settlementRequest.markSent(result.providerName(), result.providerReference());
            settlementRequest.markSucceeded(result.providerName(), result.providerReference());
            outbox.markProcessed();
            return;
        }

        if (result.timeout()) {
            settlementRequest.markUnknown(result.providerName(), result.providerReference());
            outbox.markProcessed();
            return;
        }

        settlementRequest.markFailed(result.providerName(), result.providerReference());
        releaseAllocations(settlementRequest.getId());
        outbox.markProcessed();
    }

    private void releaseAllocations(final Long settlementRequestId) {
        settlementAllocationRepository.findAllBySettlementRequestId(settlementRequestId)
                .forEach(SettlementAllocation::release);
    }
}
