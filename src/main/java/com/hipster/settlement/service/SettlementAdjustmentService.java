package com.hipster.settlement.service;

import com.hipster.settlement.domain.SettlementAdjustment;
import com.hipster.settlement.domain.SettlementAdjustmentStatus;
import com.hipster.settlement.domain.SettlementAdjustmentType;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.repository.SettlementAdjustmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementAdjustmentService {

    private static final List<SettlementAdjustmentType> RESOLVABLE_DEBIT_TYPES = List.of(
            SettlementAdjustmentType.DEBIT,
            SettlementAdjustmentType.CARRY_FORWARD_OFFSET
    );

    private final SettlementAdjustmentRepository settlementAdjustmentRepository;

    public void createOpenDebitAdjustment(final SettlementRequest settlementRequest,
                                          final String reason) {
        settlementAdjustmentRepository.findFirstBySettlementRequestIdAndStatusAndAdjustmentType(
                        settlementRequest.getId(),
                        SettlementAdjustmentStatus.OPEN,
                        SettlementAdjustmentType.DEBIT
                )
                .orElseGet(() -> settlementAdjustmentRepository.save(SettlementAdjustment.open(
                        settlementRequest.getId(),
                        settlementRequest.getUserId(),
                        SettlementAdjustmentType.DEBIT,
                        settlementRequest.getRequestedAmount(),
                        reason
                )));
    }

    public void resolveOpenDebitAdjustments(final Long userId,
                                            final Long resolvedBySettlementRequestId) {
        settlementAdjustmentRepository.findAllByUserIdAndStatusAndAdjustmentTypeIn(
                        userId,
                        SettlementAdjustmentStatus.OPEN,
                        RESOLVABLE_DEBIT_TYPES
                )
                .forEach(adjustment -> adjustment.resolveBy(resolvedBySettlementRequestId));
    }
}
