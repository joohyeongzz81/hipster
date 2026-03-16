package com.hipster.moderation.dto.response;

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
        Long moderatorId,
        ModerationStatus status,
        Integer priority,
        String metaComment,
        String submissionSnapshot,
        LocalDateTime submittedAt,
        LocalDateTime claimedAt,
        LocalDateTime claimExpiresAt,
        Long daysSinceSubmit
) {
    public static ModerationQueueItemResponse of(final ModerationQueue item, final User submitter) {
        return new ModerationQueueItemResponse(
                item.getId(),
                item.getEntityType(),
                item.getEntityId(),
                item.getSubmitterId(),
                submitter != null ? submitter.getUsername() : "Unknown",
                submitter != null ? submitter.getWeightingScore() : 0.0,
                item.getModeratorId(),
                item.getStatus(),
                item.getPriority(),
                item.getMetaComment(),
                item.getSubmissionSnapshot(),
                item.getSubmittedAt(),
                item.getClaimedAt(),
                item.getClaimExpiresAt(),
                ChronoUnit.DAYS.between(item.getSubmittedAt(), LocalDateTime.now())
        );
    }
}
