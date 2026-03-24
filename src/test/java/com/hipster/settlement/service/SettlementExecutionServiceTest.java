package com.hipster.settlement.service;

import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementPayoutOutbox;
import com.hipster.settlement.domain.SettlementPayoutOutboxStatus;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import com.hipster.settlement.gateway.PayoutGateway;
import com.hipster.settlement.gateway.PayoutGatewayResult;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementPayoutOutboxRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SettlementExecutionServiceTest {

    @InjectMocks
    private SettlementExecutionService settlementExecutionService;

    @Mock
    private SettlementPayoutOutboxRepository settlementPayoutOutboxRepository;

    @Mock
    private SettlementRequestRepository settlementRequestRepository;

    @Mock
    private SettlementAllocationRepository settlementAllocationRepository;

    @Mock
    private PayoutGateway payoutGateway;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(settlementExecutionService, "batchSize", 10);
        ReflectionTestUtils.setField(settlementExecutionService, "retryDelayMs", 30_000L);
    }

    @Test
    @DisplayName("gateway timeout이면 요청은 unknown으로 남고 allocation은 유지된다")
    void dispatchPendingRequestsMarksUnknownOnTimeout() {
        final SettlementRequest request = reservedRequest(11L, 1L, 700L);
        final SettlementPayoutOutbox outbox = pendingOutbox(31L, request.getId(), request.getRequestNo());

        given(settlementPayoutOutboxRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any(), any()))
                .willReturn(List.of(outbox));
        given(settlementPayoutOutboxRepository.updateStatusForDispatch(anyLong(), any(), eq(SettlementPayoutOutboxStatus.DISPATCHED), any(), any(), any(), any()))
                .willReturn(1);
        given(settlementPayoutOutboxRepository.findById(outbox.getId())).willReturn(Optional.of(outbox));
        given(settlementRequestRepository.findById(request.getId())).willReturn(Optional.of(request));
        given(payoutGateway.execute(request)).willReturn(PayoutGatewayResult.timeout("mock-payout", request.getRequestNo()));

        settlementExecutionService.dispatchPendingRequests();

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.UNKNOWN);
        assertThat(outbox.getStatus()).isEqualTo(SettlementPayoutOutboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("gateway failure면 요청은 failed로 닫히고 allocation은 해제된다")
    void dispatchPendingRequestsMarksFailedAndReleasesAllocations() {
        final SettlementRequest request = reservedRequest(12L, 2L, 800L);
        final SettlementPayoutOutbox outbox = pendingOutbox(32L, request.getId(), request.getRequestNo());
        final SettlementAllocation allocation = allocation(request.getId(), request.getUserId(), 88L, 800L);

        given(settlementPayoutOutboxRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any(), any()))
                .willReturn(List.of(outbox));
        given(settlementPayoutOutboxRepository.updateStatusForDispatch(anyLong(), any(), eq(SettlementPayoutOutboxStatus.DISPATCHED), any(), any(), any(), any()))
                .willReturn(1);
        given(settlementPayoutOutboxRepository.findById(outbox.getId())).willReturn(Optional.of(outbox));
        given(settlementRequestRepository.findById(request.getId())).willReturn(Optional.of(request));
        given(settlementAllocationRepository.findAllBySettlementRequestId(request.getId())).willReturn(List.of(allocation));
        given(payoutGateway.execute(request)).willReturn(PayoutGatewayResult.failure("mock-payout", request.getRequestNo(), "BANK_REJECTED"));

        settlementExecutionService.dispatchPendingRequests();

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.FAILED);
        assertThat(allocation.isActive()).isFalse();
        assertThat(outbox.getStatus()).isEqualTo(SettlementPayoutOutboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("gateway 호출 자체가 예외면 outbox는 retry 가능한 failed 상태로 남는다")
    void dispatchPendingRequestsMarksOutboxFailedWhenGatewayThrows() {
        final SettlementRequest request = reservedRequest(13L, 3L, 900L);
        final SettlementPayoutOutbox outbox = pendingOutbox(33L, request.getId(), request.getRequestNo());

        given(settlementPayoutOutboxRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any(), any()))
                .willReturn(List.of(outbox));
        given(settlementPayoutOutboxRepository.updateStatusForDispatch(anyLong(), any(), eq(SettlementPayoutOutboxStatus.DISPATCHED), any(), any(), any(), any()))
                .willReturn(1);
        given(settlementPayoutOutboxRepository.findById(outbox.getId())).willReturn(Optional.of(outbox));
        given(settlementRequestRepository.findById(request.getId())).willReturn(Optional.of(request));
        given(payoutGateway.execute(request)).willThrow(new IllegalStateException("network issue"));

        settlementExecutionService.dispatchPendingRequests();

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.RESERVED);
        assertThat(outbox.getStatus()).isEqualTo(SettlementPayoutOutboxStatus.FAILED);
        assertThat(outbox.getLastError()).contains("network issue");
    }

    private SettlementRequest reservedRequest(final Long id, final Long userId, final long amount) {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-" + id,
                userId,
                "KRW",
                amount,
                amount,
                true,
                "bank-account-snapshot"
        );
        request.markReserved();
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    private SettlementPayoutOutbox pendingOutbox(final Long id, final Long settlementRequestId, final String requestNo) {
        final SettlementPayoutOutbox outbox = SettlementPayoutOutbox.pending(settlementRequestId, "mock-payout", requestNo);
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
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
