package com.hipster.user.dto;

import com.hipster.user.domain.User;
import java.time.LocalDateTime;

public record WeightingResponse(
        Long userId,
        Double weightingScore,
        Boolean reviewBonus,
        LocalDateTime lastCalculated
) {
    public static WeightingResponse from(User user) {
        return new WeightingResponse(
                user.getId(),
                user.getWeightingScore(),
                user.getReviewBonus(),
                user.getUpdatedAt() // lastCalculated uses updatedAt
        );
    }
}
