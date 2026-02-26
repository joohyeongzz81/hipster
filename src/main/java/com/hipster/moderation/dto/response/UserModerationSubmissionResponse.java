package com.hipster.moderation.dto.response;

import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record UserModerationSubmissionResponse(
        Long id,
        EntityType entityType,
        Long entityId,
        ModerationStatus status,
        String rejectionReason,
        String moderatorComment,
        LocalDateTime submittedAt,
        LocalDateTime processedAt
) {
    public static UserModerationSubmissionResponse from(final ModerationQueue item) {
        return UserModerationSubmissionResponse.builder()
                .id(item.getId())
                .entityType(item.getEntityType())
                .entityId(item.getEntityId())
                .status(item.getStatus())
                .rejectionReason(item.getRejectionReason())
                .moderatorComment(item.getModeratorComment())
                .submittedAt(item.getSubmittedAt())
                .processedAt(item.getProcessedAt())
                .build();
    }
}
