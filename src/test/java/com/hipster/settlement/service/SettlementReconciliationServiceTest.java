package com.hipster.settlement.service;

import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import com.hipster.settlement.gateway.PayoutGateway;
import com.hipster.settlement.gateway.PayoutGatewayResult;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementReconciliationServiceTest {

    @InjectMocks
    private SettlementReconciliationService settlementReconciliationService;

    @Mock
    private SettlementRequestRepository settlementRequestRepository;

    @Mock
    private SettlementAllocationRepository settlementAllocationRepository;

    @Mock
    private PayoutGateway payoutGateway;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(settlementReconciliationService, "batchSize", 10);
    }

    @Test
    @DisplayName("reconciliation success lookup은 unknown 요청을 succeeded로 수렴시킨다")
    void reconcilePendingRequestsMarksUnknownRequestSucceeded() {
        final SettlementRequest request = request(21L, 7L, SettlementRequestStatus.UNKNOWN, "provider-ref-success");

        given(settlementRequestRepository.findAllByStatusInOrderByRequestedAtAsc(any()))
                .willReturn(List.of(request));
        given(payoutGateway.lookup(request))
                .willReturn(PayoutGatewayResult.success("mock-payout", request.getProviderReference()));

        settlementReconciliationService.reconcilePendingRequests();

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.SUCCEEDED);
        verify(settlementAllocationRepository, never()).findAllBySettlementRequestId(any());
    }

    @Test
    @DisplayName("reconciliation failure lookup은 unknown 요청을 failed로 바꾸고 allocation을 해제한다")
    void reconcilePendingRequestsMarksUnknownRequestFailedAndReleasesAllocations() {
        final SettlementRequest request = request(22L, 8L, SettlementRequestStatus.UNKNOWN, "provider-ref-failure");
        final SettlementAllocation allocation = allocation(request.getId(), request.getUserId(), 901L, 500L);

        given(settlementRequestRepository.findAllByStatusInOrderByRequestedAtAsc(any()))
                .willReturn(List.of(request));
        given(payoutGateway.lookup(request))
                .willReturn(PayoutGatewayResult.failure("mock-payout", request.getProviderReference(), "BANK_REJECTED"));
        given(settlementAllocationRepository.findAllBySettlementRequestId(request.getId()))
                .willReturn(List.of(allocation));

        settlementReconciliationService.reconcilePendingRequests();

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.FAILED);
        assertThat(allocation.isActive()).isFalse();
    }

    @Test
    @DisplayName("provider reference가 없으면 lookup 없이 그대로 둔다")
    void reconcilePendingRequestsSkipsRequestWithoutProviderReference() {
        final SettlementRequest request = request(23L, 9L, SettlementRequestStatus.SENT, "provider-ref-sent");
        ReflectionTestUtils.setField(request, "providerReference", null);

        given(settlementRequestRepository.findAllByStatusInOrderByRequestedAtAsc(any()))
                .willReturn(List.of(request));

        settlementReconciliationService.reconcilePendingRequests();

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.SENT);
        verify(payoutGateway, never()).lookup(any());
    }

    private SettlementRequest request(final Long id,
                                      final Long userId,
                                      final SettlementRequestStatus status,
                                      final String providerReference) {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-" + id,
                userId,
                "KRW",
                500L,
                500L,
                true,
                "bank-account-snapshot"
        );
        request.markReserved();
        request.markSent("mock-payout", providerReference);
        if (status == SettlementRequestStatus.UNKNOWN) {
            request.markUnknown("mock-payout", providerReference);
        }
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    private SettlementAllocation allocation(final Long settlementRequestId,
                                            final Long userId,
                                            final Long rewardLedgerEntryId,
                                            final long amount) {
        final RewardLedgerEntry rewardLedgerEntry = RewardLedgerEntry.accrued(1L, userId, "catalog_bootstrap_v1", amount);
        ReflectionTestUtils.setField(rewardLedgerEntry, "id", rewardLedgerEntryId);
        return SettlementAllocation.reserve(settlementRequestId, rewardLedgerEntry.getId(), userId, amount);
    }
}
