package com.hipster.moderation.dto.request;

import com.hipster.moderation.domain.RejectionReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RejectRequest(
        @NotNull(message = "Rejection reason is required.")
        RejectionReason reason,

        @NotBlank(message = "Comment is required.")
        @Size(min = 10, message = "Comment must be at least 10 characters.")
        String comment
) {
}
