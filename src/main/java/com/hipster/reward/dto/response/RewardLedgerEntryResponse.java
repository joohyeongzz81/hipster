package com.hipster.reward.dto.response;

import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.domain.RewardLedgerEntryStatus;
import com.hipster.reward.domain.RewardLedgerEntryType;

import java.time.LocalDateTime;

public record RewardLedgerEntryResponse(
        Long id,
        Long approvalId,
        Long userId,
        String campaignCode,
        RewardLedgerEntryType entryType,
        RewardLedgerEntryStatus entryStatus,
        long pointsDelta,
        Long referenceEntryId,
        String reason,
        LocalDateTime createdAt
) {
    public static RewardLedgerEntryResponse from(final RewardLedgerEntry entry) {
        return new RewardLedgerEntryResponse(
                entry.getId(),
                entry.getApprovalId(),
                entry.getUserId(),
                entry.getCampaignCode(),
                entry.getEntryType(),
                entry.getEntryStatus(),
                entry.getPointsDelta(),
                entry.getReferenceEntryId(),
                entry.getReason(),
                entry.getCreatedAt()
        );
    }
}
