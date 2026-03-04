package com.hipster.chart.algorithm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 베이지안 점수 산출 순수 도메인 객체 (POJO).
 * Spring 의존성 없음. 단위 테스트 용이.
 *
 * <pre>
 * score = (C * m + weightedScoreSum) / (C + weightedCountSum)
 * isEsoteric = weightedCountSum < m * k
 * </pre>
 *
 * @param m 사전 가중치 (Prior Weight) — 전체 통계에 수렴시키는 강도
 * @param k is_esoteric 판정 배수. weightedCountSum < m*k 이면 esoteric
 */
public class BayesianScoreCalculator {

    private static final int SCORE_SCALE = 10;

    private final BigDecimal m;
    private final BigDecimal k;

    public BayesianScoreCalculator(BigDecimal m, BigDecimal k) {
        this.m = m;
        this.k = k;
    }

    /**
     * 베이지안 점수를 계산한다.
     *
     * @param weightedScoreSum  앨범의 가중 점수 합 (release_rating_summary.weighted_score_sum)
     * @param weightedCountSum  앨범의 가중 수    (release_rating_summary.weighted_count_sum)
     * @param C                 글로벌 가중 평균  (배치 사이클 시작 시 1회 산출해 재사용)
     * @return BayesianResult(score, isEsoteric)
     */
    public BayesianResult calculate(BigDecimal weightedScoreSum, BigDecimal weightedCountSum, BigDecimal C) {
        boolean isEsoteric = weightedCountSum.compareTo(m.multiply(k)) < 0;

        if (weightedCountSum.compareTo(BigDecimal.ZERO) == 0) {
            return new BayesianResult(C.setScale(SCORE_SCALE, RoundingMode.HALF_UP), true);
        }

        // score = (C * m + weightedScoreSum) / (m + weightedCountSum)
        BigDecimal numerator = C.multiply(m).add(weightedScoreSum);
        BigDecimal denominator = m.add(weightedCountSum);
        BigDecimal score = numerator.divide(denominator, SCORE_SCALE, RoundingMode.HALF_UP);

        return new BayesianResult(score, isEsoteric);
    }
}
