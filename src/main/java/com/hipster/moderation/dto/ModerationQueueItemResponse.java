package com.hipster.moderation.dto;

import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.user.domain.User;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public record ModerationQueueItemResponse(
        Long id,
        EntityType entityType,
        Long entityId,
        Long submitterId,
        String submitterUsername,
        Double submitterWeighting,
        ModerationStatus status,
        Integer priority,
        String metaComment,
        LocalDateTime submittedAt,
        Long daysSinceSubmit
) {
    public static ModerationQueueItemResponse of(ModerationQueue item, User submitter) {
        return new ModerationQueueItemResponse(
                item.getId(),
                item.getEntityType(),
                item.getEntityId(),
                item.getSubmitterId(),
                submitter != null ? submitter.getUsername() : "Unknown",
                submitter != null ? submitter.getWeightingScore() : 0.0,
                item.getStatus(),
                item.getPriority(),
                item.getMetaComment(),
                item.getSubmittedAt(),
                ChronoUnit.DAYS.between(item.getSubmittedAt(), LocalDateTime.now())
        );
    }
}
