package com.hipster.rating.dto.response;

import java.time.LocalDateTime;

public record UserRatingResponse(
        Long ratingId,
        Long releaseId,
        String releaseTitle,
        String artistName,
        Double score,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
