package com.hipster.settlement.repository;

import com.hipster.settlement.domain.SettlementAdjustment;
import com.hipster.settlement.domain.SettlementAdjustmentStatus;
import com.hipster.settlement.domain.SettlementAdjustmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementAdjustmentRepository extends JpaRepository<SettlementAdjustment, Long> {

    Optional<SettlementAdjustment> findFirstBySettlementRequestIdAndStatusAndAdjustmentType(Long settlementRequestId,
                                                                                            SettlementAdjustmentStatus status,
                                                                                            SettlementAdjustmentType adjustmentType);

    List<SettlementAdjustment> findAllByUserIdAndStatusAndAdjustmentTypeIn(Long userId,
                                                                           SettlementAdjustmentStatus status,
                                                                           Collection<SettlementAdjustmentType> types);

    @Query("""
            select coalesce(sum(adjustment.amountDelta), 0)
            from SettlementAdjustment adjustment
            where adjustment.userId = :userId
              and adjustment.status = :status
            """)
    Long sumAmountDeltaByUserIdAndStatus(@Param("userId") Long userId,
                                         @Param("status") SettlementAdjustmentStatus status);

    @Query("""
            select coalesce(sum(adjustment.amountDelta), 0)
            from SettlementAdjustment adjustment
            where adjustment.userId = :userId
              and adjustment.status = :status
              and adjustment.adjustmentType in :types
            """)
    Long sumAmountDeltaByUserIdAndStatusAndAdjustmentTypeIn(@Param("userId") Long userId,
                                                            @Param("status") SettlementAdjustmentStatus status,
                                                            @Param("types") Collection<SettlementAdjustmentType> types);
}
