package com.hipster.release.dto.request;

import com.hipster.release.domain.ReleaseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateReleaseRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 500, message = "Title must be less than 500 characters")
        String title,

        @NotNull(message = "Artist ID is required")
        Long artistId,

        Long genreId,

        @NotNull(message = "Release type is required")
        ReleaseType releaseType,

        @NotNull(message = "Release date is required")
        LocalDate releaseDate,

        @Size(max = 100)
        String catalogNumber,

        @Size(max = 100)
        String label,

        @NotBlank(message = "Meta comment is required")
        @Size(min = 10, max = 2000, message = "Meta comment must be between 10 and 2000 characters")
        String metaComment
) {
}
