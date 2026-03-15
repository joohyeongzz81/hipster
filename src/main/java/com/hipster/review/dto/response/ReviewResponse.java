package com.hipster.review.dto.response;

import com.hipster.review.domain.Review;

import java.time.LocalDateTime;

public record ReviewResponse(
        Long id,
        Long userId,
        String username,
        Long releaseId,
        String content,
        Boolean isPublished,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ReviewResponse of(final Review review, final String username) {
        return new ReviewResponse(
                review.getId(),
                review.getUserId(),
                username,
                review.getReleaseId(),
                review.getContent(),
                review.getIsPublished(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
