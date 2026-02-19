package com.hipster.chart.dto;

import lombok.Builder;

@Builder
public record ChartEntryResponse(
        Long rank,
        Long releaseId,
        String title,
        String artistName,
        Integer releaseYear,
        Double bayesianScore,
        Double weightedAvgRating,
        Long totalRatings,
        Boolean isEsoteric
) {
}
