package com.hipster.release.dto.response;

import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import java.time.LocalDate;

public record ReleaseSummaryResponse(
        Long id,
        String title,
        Long artistId,
        String artistName,
        ReleaseType releaseType,
        LocalDate releaseDate,
        Double averageRating,
        Integer totalRatings
) {
    public static ReleaseSummaryResponse from(final Release release) {
        return new ReleaseSummaryResponse(
                release.getId(),
                release.getTitle(),
                release.getArtistId(),
                "Unknown Artist", // TODO: Fetch artist name from Artist service/repository
                release.getReleaseType(),
                release.getReleaseDate(),
                0.0, // TODO: Fetch rating stats
                0    // TODO: Fetch rating stats
        );
    }
}
