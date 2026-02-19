package com.hipster.chart.dto;

import com.hipster.release.domain.ReleaseType;

public record ChartFilterRequest(
        Long genreId,
        Integer year,
        ReleaseType releaseType,
        Boolean includeEsoteric
) {
}
