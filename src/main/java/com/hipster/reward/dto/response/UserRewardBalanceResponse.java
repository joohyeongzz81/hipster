package com.hipster.reward.dto.response;

public record UserRewardBalanceResponse(
        Long userId,
        String campaignCode,
        long totalPoints,
        boolean activeParticipation
) {
}
