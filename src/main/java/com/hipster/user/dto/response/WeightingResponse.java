package com.hipster.user.dto.response;

import com.hipster.user.domain.User;
import com.hipster.user.domain.UserWeightStats;
import java.time.LocalDateTime;

public record WeightingResponse(
        Long userId,
        Double weightingScore,
        Boolean reviewBonus,
        LocalDateTime lastCalculated
) {
    public static WeightingResponse from(final User user, final UserWeightStats stats) {
        return new WeightingResponse(
                user.getId(),
                user.getWeightingScore(),
                hasReviewBonus(stats),
                stats != null ? stats.getLastCalculatedAt() : null
        );
    }

    private static boolean hasReviewBonus(final UserWeightStats stats) {
        return stats != null && stats.getReviewCount() != null && stats.getReviewCount() > 0;
    }
}
