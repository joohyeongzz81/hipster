package com.hipster.moderation.dto.request;

import jakarta.validation.constraints.NotNull;

public record ReassignRequest(
        @NotNull(message = "Target moderator id is required.")
        Long targetModeratorId
) {
}
