package com.hipster.chart.algorithm;

import java.math.BigDecimal;

/**
 * 베이지안 점수 계산 결과.
 *
 * @param score      산출된 베이지안 점수
 * @param isEsoteric 가중 투표 수가 임계치 미달인 앨범 여부 (차트 필터링 기준)
 */
public record BayesianResult(BigDecimal score, boolean isEsoteric) {
}
