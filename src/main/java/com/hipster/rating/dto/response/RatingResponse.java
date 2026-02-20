package com.hipster.rating.dto.response;

import com.hipster.rating.domain.Rating;
import java.time.LocalDateTime;

public record RatingResponse(
        Long id,
        Long userId,
        String username,
        Long releaseId,
        Double score,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RatingResponse from(final Rating rating, final String username) {
        return new RatingResponse(
                rating.getId(),
                rating.getUserId(),
                username,
                rating.getReleaseId(),
                rating.getScore(),
                rating.getCreatedAt(),
                rating.getUpdatedAt()
        );
    }
}
