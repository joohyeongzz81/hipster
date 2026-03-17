package com.hipster.reward.dto.response;

import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.reward.domain.RewardApprovalAccrualState;

import java.util.List;

public record RewardApprovalAccrualResponse(
        Long approvalId,
        ModerationStatus approvalStatus,
        Long userId,
        String campaignCode,
        RewardApprovalAccrualState accrualState,
        long netPoints,
        List<RewardLedgerEntryResponse> entries
) {
}
