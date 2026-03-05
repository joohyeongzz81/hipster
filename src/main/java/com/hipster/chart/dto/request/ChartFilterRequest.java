package com.hipster.chart.dto.request;

import com.hipster.global.domain.Language;
import com.hipster.release.domain.ReleaseType;

public record ChartFilterRequest(
        Long genreId,
        Long descriptorId,
        Long locationId,
        Language language,
        Integer year,
        ReleaseType releaseType,
        Boolean includeEsoteric
) {
}
