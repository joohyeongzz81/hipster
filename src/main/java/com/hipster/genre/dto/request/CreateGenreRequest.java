package com.hipster.genre.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGenreRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be less than 255 characters")
        String name,

        Long parentId,

        @Size(max = 3000, message = "Description must be less than 3000 characters")
        String description,

        Boolean isDescriptor,

        @NotBlank(message = "Meta comment is required")
        @Size(min = 10, max = 2000, message = "Meta comment must be between 10 and 2000 characters")
        String metaComment
) {
}
