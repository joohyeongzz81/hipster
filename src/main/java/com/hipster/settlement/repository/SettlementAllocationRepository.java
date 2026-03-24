package com.hipster.settlement.repository;

import com.hipster.settlement.domain.SettlementAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementAllocationRepository extends JpaRepository<SettlementAllocation, Long> {

    List<SettlementAllocation> findAllBySettlementRequestId(Long settlementRequestId);

    List<SettlementAllocation> findAllByUserIdAndActiveTrue(Long userId);

    @Query("""
            select coalesce(sum(allocation.allocatedAmount), 0)
            from SettlementAllocation allocation
            where allocation.userId = :userId
              and allocation.active = true
            """)
    Long sumAllocatedAmountByUserIdAndActiveTrue(@Param("userId") Long userId);

    @Query("""
            select allocation.rewardLedgerEntryId
            from SettlementAllocation allocation
            where allocation.userId = :userId
              and allocation.active = true
            """)
    List<Long> findActiveRewardLedgerEntryIdsByUserId(@Param("userId") Long userId);
}
