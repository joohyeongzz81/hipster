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
    public static ReleaseSummaryResponse of(final Release release, final String artistName,
                                            final Double averageRating, final Integer totalRatings) {
        return new ReleaseSummaryResponse(
                release.getId(),
                release.getTitle(),
                release.getArtistId(),
                artistName,
                release.getReleaseType(),
                release.getReleaseDate(),
                averageRating,
                totalRatings
        );
    }
}
