package com.hipster.batch.chart.dto;

/**
 * Processor → Writer 구간에서 ChartScore 계산 결과를 전달하는 DTO.
 */
public record ChartScoreDto(
        Long releaseId,
        Double bayesianScore,
        Double weightedAvgRating,
        Double effectiveVotes,
        Long totalRatings,
        Boolean isEsoteric
) {
}

