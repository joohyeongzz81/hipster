package com.hipster.moderation.dto;

import com.hipster.moderation.domain.ModerationStatus;

public record ModerationSubmitResponse(
        Long queueId,
        ModerationStatus status,
        String message,
        String estimatedReviewTime
) {
}
