package com.hipster.settlement.service;

import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import com.hipster.settlement.gateway.PayoutGateway;
import com.hipster.settlement.gateway.PayoutGatewayResult;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementReconciliationService {

    private static final List<SettlementRequestStatus> RECONCILABLE_STATUSES = List.of(
            SettlementRequestStatus.SENT,
            SettlementRequestStatus.UNKNOWN
    );

    private final SettlementRequestRepository settlementRequestRepository;
    private final SettlementAllocationRepository settlementAllocationRepository;
    private final PayoutGateway payoutGateway;

    @Value("${hipster.settlement.reconciliation.batch-size:50}")
    private int batchSize;

    @Transactional
    public void reconcilePendingRequests() {
        final List<SettlementRequest> candidates =
                settlementRequestRepository.findAllByStatusInOrderByRequestedAtAsc(RECONCILABLE_STATUSES);
        final int limit = Math.max(batchSize, 1);

        for (int index = 0; index < candidates.size() && index < limit; index++) {
            final SettlementRequest settlementRequest = candidates.get(index);
            try {
                reconcileRequest(settlementRequest);
            } catch (RuntimeException exception) {
                log.warn("Settlement reconciliation lookup failed. requestNo={}, providerReference={}",
                        settlementRequest.getRequestNo(),
                        settlementRequest.getProviderReference(),
                        exception);
            }
        }
    }

    private void reconcileRequest(final SettlementRequest settlementRequest) {
        if (settlementRequest.getProviderReference() == null || settlementRequest.getProviderReference().isBlank()) {
            return;
        }

        final PayoutGatewayResult result = payoutGateway.lookup(settlementRequest);
        if (result == null || result.timeout()) {
            return;
        }

        final String providerName = resolveProviderName(settlementRequest, result);
        final String providerReference = resolveProviderReference(settlementRequest, result);

        if (result.succeeded()) {
            settlementRequest.markSucceeded(providerName, providerReference);
            return;
        }

        settlementRequest.markFailed(providerName, providerReference);
        releaseAllocations(settlementRequest.getId());
    }

    private String resolveProviderName(final SettlementRequest settlementRequest, final PayoutGatewayResult result) {
        if (result.providerName() != null && !result.providerName().isBlank()) {
            return result.providerName();
        }
        return settlementRequest.getProviderName();
    }

    private String resolveProviderReference(final SettlementRequest settlementRequest,
                                            final PayoutGatewayResult result) {
        if (result.providerReference() != null && !result.providerReference().isBlank()) {
            return result.providerReference();
        }
        return settlementRequest.getProviderReference();
    }

    private void releaseAllocations(final Long settlementRequestId) {
        settlementAllocationRepository.findAllBySettlementRequestId(settlementRequestId)
                .forEach(SettlementAllocation::release);
    }
}
