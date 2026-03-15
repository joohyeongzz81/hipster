package com.hipster.chart.dto.request;

import com.hipster.global.domain.Language;
import com.hipster.release.domain.ReleaseType;

import java.util.List;

public record ChartFilterRequest(
        Long genreId,
        List<Long> genreIds,
        Long descriptorId,
        Long locationId,
        Language language,
        Integer year,
        ReleaseType releaseType,
        Boolean includeEsoteric
) {
    public static ChartFilterRequest ofSingleGenre(
            final Long genreId,
            final Long descriptorId,
            final Long locationId,
            final Language language,
            final Integer year,
            final ReleaseType releaseType,
            final Boolean includeEsoteric
    ) {
        return new ChartFilterRequest(
                genreId,
                genreId != null ? List.of(genreId) : null,
                descriptorId,
                locationId,
                language,
                year,
                releaseType,
                includeEsoteric
        );
    }

    public static ChartFilterRequest empty() {
        return new ChartFilterRequest(null, null, null, null, null, null, null, false);
    }

    public List<Long> normalizedGenreIds() {
        if (genreIds != null && !genreIds.isEmpty()) {
            return genreIds;
        }
        if (genreId != null) {
            return List.of(genreId);
        }
        return List.of();
    }

    public boolean hasGenreFilter() {
        return !normalizedGenreIds().isEmpty();
    }
}
