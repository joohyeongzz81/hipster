package com.hipster.moderation.dto;

import com.hipster.moderation.domain.EntityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitRequest(
        @NotNull(message = "Entity type is required") EntityType entityType,
        @NotNull(message = "Entity ID is required") Long entityId,
        @NotBlank(message = "Source URL or comment is required") String metaComment
) {
}
