package com.hipster.batch.dto;

import java.time.LocalDateTime;

public record UserWeightingStatsDto(
        long ratingCount,
        double ratingVariance,
        LocalDateTime maxRatingDate,
        long reviewCount,
        double reviewAvgLength,
        LocalDateTime maxReviewDate
) {
    public LocalDateTime getLastActiveDate(LocalDateTime userLastActiveDate) {
        LocalDateTime lastActive = userLastActiveDate != null ? userLastActiveDate : LocalDateTime.now();
        if (maxRatingDate != null && maxRatingDate.isAfter(lastActive)) {
            lastActive = maxRatingDate;
        }
        if (maxReviewDate != null && maxReviewDate.isAfter(lastActive)) {
            lastActive = maxReviewDate;
        }
        return lastActive;
    }
}
