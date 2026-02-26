package com.hipster.review.dto.response;

import java.time.LocalDateTime;

public record UserReviewResponse(
        Long reviewId,
        Long releaseId,
        String releaseTitle,
        String artistName,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
