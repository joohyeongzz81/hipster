package com.hipster.reward.repository;

import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.domain.RewardLedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RewardLedgerEntryRepository extends JpaRepository<RewardLedgerEntry, Long> {

    Optional<RewardLedgerEntry> findByApprovalIdAndCampaignCodeAndEntryType(Long approvalId,
                                                                            String campaignCode,
                                                                            RewardLedgerEntryType entryType);

    List<RewardLedgerEntry> findAllByApprovalIdOrderByCreatedAtAsc(Long approvalId);

    @Query("select coalesce(sum(entry.pointsDelta), 0) from RewardLedgerEntry entry where entry.userId = :userId")
    Long sumPointsDeltaByUserId(@Param("userId") Long userId);
}
