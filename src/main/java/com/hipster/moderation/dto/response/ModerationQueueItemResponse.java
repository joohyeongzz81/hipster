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
        Long daysSinceSubmit,
        Long submittedAgeHours,
        Boolean slaBreached
) {
    public static ModerationQueueItemResponse of(final ModerationQueue item,
                                                 final User submitter,
                                                 final LocalDateTime referenceTime,
                                                 final long slaTargetHours) {
        final long daysSinceSubmit = ChronoUnit.DAYS.between(item.getSubmittedAt(), referenceTime);
        final long submittedAgeHours = ChronoUnit.HOURS.between(item.getSubmittedAt(), referenceTime);
        final boolean slaBreached = isOpenItem(item) && submittedAgeHours >= slaTargetHours;
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
                daysSinceSubmit,
                submittedAgeHours,
                slaBreached
        );
    }

    private static boolean isOpenItem(final ModerationQueue item) {
        return item.getStatus() == ModerationStatus.PENDING
                || item.getStatus() == ModerationStatus.UNDER_REVIEW;
    }
}
