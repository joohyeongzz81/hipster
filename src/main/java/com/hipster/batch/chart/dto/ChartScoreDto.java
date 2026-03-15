package com.hipster.batch.chart.dto;

import com.hipster.release.domain.ReleaseType;
import com.hipster.global.domain.Language;

/**
 * Processor → Writer 구간에서 ChartScore 계산 결과를 전달하는 DTO.
 */
public record ChartScoreDto(
        Long releaseId,
        Double bayesianScore,
        Double weightedAvgRating,
        Double effectiveVotes,
        Long totalRatings,
        Boolean isEsoteric,
        String genreIds,
        ReleaseType releaseType,
        Integer releaseYear,
        String descriptorIds,
        Long locationId,
        String languages
) {
}
