package com.hipster.artist.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateArtistRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be less than 255 characters")
        String name,

        @Size(max = 5000, message = "Description must be less than 5000 characters")
        String description,

        @Min(value = 1, message = "Formed year must be larger than 0")
        @Max(value = 2026, message = "Formed year cannot be in the future")
        Integer formedYear,

        @Size(max = 100, message = "Country must be less than 100 characters")
        String country,

        @NotBlank(message = "Meta comment is required")
        @Size(min = 10, max = 2000, message = "Meta comment must be between 10 and 2000 characters")
        String metaComment
) {
}
