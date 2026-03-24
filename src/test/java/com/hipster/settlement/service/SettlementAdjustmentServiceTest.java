package com.hipster.settlement.service;

import com.hipster.settlement.domain.SettlementAdjustment;
import com.hipster.settlement.domain.SettlementAdjustmentStatus;
import com.hipster.settlement.domain.SettlementAdjustmentType;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.repository.SettlementAdjustmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementAdjustmentServiceTest {

    @InjectMocks
    private SettlementAdjustmentService settlementAdjustmentService;

    @Mock
    private SettlementAdjustmentRepository settlementAdjustmentRepository;

    @Test
    @DisplayName("성공 후 실패가 오면 open debit adjustment를 한 번만 만든다")
    void createOpenDebitAdjustmentCreatesSingleOpenDebitRow() {
        final SettlementRequest settlementRequest = request(41L, 5L, 800L);

        given(settlementAdjustmentRepository.findFirstBySettlementRequestIdAndStatusAndAdjustmentType(
                settlementRequest.getId(),
                SettlementAdjustmentStatus.OPEN,
                SettlementAdjustmentType.DEBIT
        )).willReturn(Optional.empty());
        given(settlementAdjustmentRepository.save(any(SettlementAdjustment.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        settlementAdjustmentService.createOpenDebitAdjustment(
                settlementRequest,
                "Failure webhook received after payout had already succeeded."
        );

        verify(settlementAdjustmentRepository).save(any(SettlementAdjustment.class));
    }

    @Test
    @DisplayName("이미 열린 debit adjustment가 있으면 중복 생성하지 않는다")
    void createOpenDebitAdjustmentSkipsWhenOpenAdjustmentAlreadyExists() {
        final SettlementRequest settlementRequest = request(42L, 6L, 900L);
        final SettlementAdjustment existingAdjustment = SettlementAdjustment.open(
                settlementRequest.getId(),
                settlementRequest.getUserId(),
                SettlementAdjustmentType.DEBIT,
                settlementRequest.getRequestedAmount(),
                "existing"
        );

        given(settlementAdjustmentRepository.findFirstBySettlementRequestIdAndStatusAndAdjustmentType(
                settlementRequest.getId(),
                SettlementAdjustmentStatus.OPEN,
                SettlementAdjustmentType.DEBIT
        )).willReturn(Optional.of(existingAdjustment));

        settlementAdjustmentService.createOpenDebitAdjustment(settlementRequest, "ignored");

        verify(settlementAdjustmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("새 정산 요청이 생성되면 열린 debit adjustment들을 resolve한다")
    void resolveOpenDebitAdjustmentsResolvesAllOpenDebitRows() {
        final SettlementAdjustment debitAdjustment = SettlementAdjustment.open(11L, 7L, SettlementAdjustmentType.DEBIT, 300L, "debit");
        final SettlementAdjustment carryForwardAdjustment = SettlementAdjustment.open(12L, 7L, SettlementAdjustmentType.CARRY_FORWARD_OFFSET, 200L, "carry");

        given(settlementAdjustmentRepository.findAllByUserIdAndStatusAndAdjustmentTypeIn(
                eq(7L),
                eq(SettlementAdjustmentStatus.OPEN),
                any()
        )).willReturn(List.of(debitAdjustment, carryForwardAdjustment));

        settlementAdjustmentService.resolveOpenDebitAdjustments(7L, 88L);

        assertThat(debitAdjustment.getStatus()).isEqualTo(SettlementAdjustmentStatus.RESOLVED);
        assertThat(debitAdjustment.getResolvedBySettlementRequestId()).isEqualTo(88L);
        assertThat(carryForwardAdjustment.getStatus()).isEqualTo(SettlementAdjustmentStatus.RESOLVED);
        assertThat(carryForwardAdjustment.getResolvedBySettlementRequestId()).isEqualTo(88L);
    }

    private SettlementRequest request(final Long id, final Long userId, final long requestedAmount) {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-" + id,
                userId,
                "KRW",
                requestedAmount,
                requestedAmount,
                true,
                "bank-account-snapshot"
        );
        request.markReserved();
        request.markSent("mock-payout", "provider-ref-" + id);
        request.markSucceeded("mock-payout", "provider-ref-" + id);
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }
}
