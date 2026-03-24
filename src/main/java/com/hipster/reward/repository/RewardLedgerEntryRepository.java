package com.hipster.reward.repository;

import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.domain.RewardLedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RewardLedgerEntryRepository extends JpaRepository<RewardLedgerEntry, Long> {

    Optional<RewardLedgerEntry> findByApprovalIdAndCampaignCodeAndEntryType(Long approvalId,
                                                                            String campaignCode,
                                                                            RewardLedgerEntryType entryType);

    List<RewardLedgerEntry> findAllByApprovalIdOrderByCreatedAtAsc(Long approvalId);

    List<RewardLedgerEntry> findAllByApprovalIdInOrderByCreatedAtAsc(List<Long> approvalIds);

    @Query("select coalesce(sum(entry.pointsDelta), 0) from RewardLedgerEntry entry where entry.userId = :userId")
    Long sumPointsDeltaByUserId(@Param("userId") Long userId);

    @Query("""
            select coalesce(sum(entry.pointsDelta), 0)
            from RewardLedgerEntry entry
            where entry.userId = :userId
              and entry.campaignCode = :campaignCode
            """)
    Long sumPointsDeltaByUserIdAndCampaignCode(@Param("userId") Long userId,
                                               @Param("campaignCode") String campaignCode);

    @Query("""
            select accrual
            from RewardLedgerEntry accrual
            where accrual.userId = :userId
              and accrual.entryType = com.hipster.reward.domain.RewardLedgerEntryType.ACCRUAL
              and accrual.entryStatus = com.hipster.reward.domain.RewardLedgerEntryStatus.ACCRUED
              and accrual.pointsDelta > 0
              and accrual.createdAt <= :availableFrom
              and not exists (
                    select reversal.id
                    from RewardLedgerEntry reversal
                    where reversal.referenceEntryId = accrual.id
                      and reversal.entryType = com.hipster.reward.domain.RewardLedgerEntryType.REVERSAL
                      and reversal.entryStatus = com.hipster.reward.domain.RewardLedgerEntryStatus.REVERSED
              )
            order by accrual.createdAt asc, accrual.id asc
            """)
    List<RewardLedgerEntry> findEligibleSettlementEntries(@Param("userId") Long userId,
                                                          @Param("availableFrom") LocalDateTime availableFrom);
}
