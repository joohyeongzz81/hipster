package com.hipster.review.dto;

import com.hipster.review.domain.Review;

import java.time.LocalDateTime;

public record ReviewResponse(
        Long id,
        Long userId,
        String username,
        Long releaseId,
        String content,
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
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
