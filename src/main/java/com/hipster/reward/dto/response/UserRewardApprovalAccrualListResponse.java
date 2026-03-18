package com.hipster.reward.dto.response;

import java.util.List;

public record UserRewardApprovalAccrualListResponse(
        Long userId,
        String campaignCode,
        int totalApprovals,
        List<RewardApprovalAccrualResponse> items
) {
}
