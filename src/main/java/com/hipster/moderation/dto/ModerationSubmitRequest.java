package com.hipster.moderation.dto;

import com.hipster.moderation.domain.EntityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ModerationSubmitRequest(
        @NotNull(message = "Entity Type is required.")
        EntityType entityType,

        Long entityId,

        @NotBlank(message = "Meta comment is required.")
        @Size(min = 20, message = "Meta comment must be at least 20 characters.")
        String metaComment
) {
}
