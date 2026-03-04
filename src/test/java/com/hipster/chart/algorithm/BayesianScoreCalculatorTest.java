package com.hipster.chart.algorithm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BayesianScoreCalculator 단위 테스트.
 * Spring 컨텍스트 없이 순수 POJO 단독 실행.
 *
 * 테스트 실행 방법:
 *   ./gradlew test --tests "com.hipster.chart.algorithm.BayesianScoreCalculatorTest"
 */
class BayesianScoreCalculatorTest {

    // m = 50 (Prior Weight), k = 1.0 (esoteric 판정 배수)
    // → weightedCountSum < 50 * 1.0 = 50 이면 esoteric
    private static final BigDecimal M = BigDecimal.valueOf(50.0);
    private static final BigDecimal K = BigDecimal.valueOf(1.0);
    private static final BigDecimal C = BigDecimal.valueOf(3.5); // 글로벌 가중 평균 (테스트용 고정값)
    private static final double TOLERANCE = 0.0001;

    private BayesianScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new BayesianScoreCalculator(M, K);
    }

    // -------------------------------------------------------
    // 1. 수렴 검증
    // -------------------------------------------------------

    @Test
    @DisplayName("투표 수가 0일 때 점수는 C(글로벌 평균)와 동일해야 한다")
    void whenNoVotes_scoreShouldEqualC() {
        BayesianResult result = calculator.calculate(
                BigDecimal.ZERO,  // weightedScoreSum
                BigDecimal.ZERO,  // weightedCountSum
                C
        );

        assertThat(result.score().doubleValue()).isCloseTo(C.doubleValue(), org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(result.isEsoteric()).isTrue();
    }

    @Test
    @DisplayName("투표 수가 매우 적을 때 점수는 C(글로벌 평균)로 수렴해야 한다")
    void whenFewVotes_scoreShouldConvergeToC() {
        // weightedCountSum = 1 (M=50 대비 극소수)
        BigDecimal weightedCountSum = BigDecimal.valueOf(1.0);
        BigDecimal weightedScoreSum = BigDecimal.valueOf(5.0); // 자체 평균 R = 5.0

        BayesianResult result = calculator.calculate(weightedScoreSum, weightedCountSum, C);

        // score = (C*M + weightedScoreSum) / (M + weightedCountSum) ≈ C (투표 수 극소)
        double expected = (C.doubleValue() * M.doubleValue() + weightedScoreSum.doubleValue())
                / (M.doubleValue() + weightedCountSum.doubleValue());

        assertThat(result.score().doubleValue()).isCloseTo(expected, org.assertj.core.data.Offset.offset(TOLERANCE));
        // C(3.5)와 자체 평균(5.0) 사이에서 C에 가까워야 함
        assertThat(result.score().doubleValue()).isLessThan(4.0);
    }

    @Test
    @DisplayName("투표 수가 매우 많을 때 점수는 자체 평균 R로 수렴해야 한다")
    void whenManyVotes_scoreShouldConvergeToSelfAverage() {
        // 자체 평균 R = 4.8, weightedCountSum = 10_000 (M=50 대비 압도적)
        double selfAvgR = 4.8;
        double weightedCountSumVal = 10_000.0;
        BigDecimal weightedCountSum = BigDecimal.valueOf(weightedCountSumVal);
        BigDecimal weightedScoreSum = BigDecimal.valueOf(selfAvgR * weightedCountSumVal);

        BayesianResult result = calculator.calculate(weightedScoreSum, weightedCountSum, C);

        // score ≈ R (투표 수가 압도적으로 많아 C 영향이 희석됨)
        assertThat(result.score().doubleValue()).isCloseTo(selfAvgR, org.assertj.core.data.Offset.offset(0.01));
    }

    // -------------------------------------------------------
    // 2. is_esoteric 판정 검증
    // -------------------------------------------------------

    @Test
    @DisplayName("weightedCountSum < M * K 이면 isEsoteric = true")
    void whenWeightedCountSumBelowThreshold_isEsotericTrue() {
        // 임계치 = 50 * 1.0 = 50 → weightedCountSum = 49.9 → esoteric
        BigDecimal weightedCountSum = BigDecimal.valueOf(49.9);
        BigDecimal weightedScoreSum = BigDecimal.valueOf(49.9 * 4.0);

        BayesianResult result = calculator.calculate(weightedScoreSum, weightedCountSum, C);

        assertThat(result.isEsoteric()).isTrue();
    }

    @Test
    @DisplayName("weightedCountSum >= M * K 이면 isEsoteric = false")
    void whenWeightedCountSumAtOrAboveThreshold_isEsotericFalse() {
        // 임계치 = 50 * 1.0 = 50 → weightedCountSum = 50.0 → not esoteric
        BigDecimal weightedCountSum = BigDecimal.valueOf(50.0);
        BigDecimal weightedScoreSum = BigDecimal.valueOf(50.0 * 4.0);

        BayesianResult result = calculator.calculate(weightedScoreSum, weightedCountSum, C);

        assertThat(result.isEsoteric()).isFalse();
    }

    // -------------------------------------------------------
    // 3. 공식 정합성 검증
    // -------------------------------------------------------

    @Test
    @DisplayName("베이지안 공식 계산값이 수작업 계산값과 일치해야 한다")
    void bayesianFormula_shouldMatchManualCalculation() {
        BigDecimal weightedScoreSum = BigDecimal.valueOf(200.0); // weighted_score_sum
        BigDecimal weightedCountSum = BigDecimal.valueOf(60.0);  // weighted_count_sum

        BayesianResult result = calculator.calculate(weightedScoreSum, weightedCountSum, C);

        // 수작업: (C*M + weightedScoreSum) / (C + weightedCountSum)
        // = (3.5 * 50 + 200) / (3.5 + 60)
        // = (175 + 200) / 63.5
        // = 375 / 63.5 ≈ 5.9055...
        double manualScore = (C.doubleValue() * M.doubleValue() + 200.0)
                / (M.doubleValue() + 60.0);

        assertThat(result.score().doubleValue()).isCloseTo(manualScore, org.assertj.core.data.Offset.offset(TOLERANCE));
    }

    // -------------------------------------------------------
    // [시나리오 1] 극단 점수는 투표 수가 부족하면 희석된다
    // -------------------------------------------------------

    @Test
    @DisplayName("[시나리오1] 만점 앨범(2표)이 평범한 앨범(200표)보다 베이지안 점수가 낮아야 한다")
    void scenario1_highSelfAvgWithFewVotes_ranksLowerThanModerateWithManyVotes() {
        // 앨범 A: 자체 평균 5.0 (만점), 2표
        double albumA_avg = 5.0;
        double albumA_count = 2.0;
        BayesianResult albumA = calculator.calculate(
                BigDecimal.valueOf(albumA_avg * albumA_count),
                BigDecimal.valueOf(albumA_count),
                C
        );

        // 앨범 B: 자체 평균 4.0, 200표
        double albumB_avg = 4.0;
        double albumB_count = 200.0;
        BayesianResult albumB = calculator.calculate(
                BigDecimal.valueOf(albumB_avg * albumB_count),
                BigDecimal.valueOf(albumB_count),
                C
        );

        // 만점이지만 2표뿐인 A가 200표 받은 B보다 베이지안 점수가 낮아야 함
        assertThat(albumA.score()).isLessThan(albumB.score());

        // A는 esoteric, B는 mainstream
        assertThat(albumA.isEsoteric()).isTrue();
        assertThat(albumB.isEsoteric()).isFalse();
    }

    // -------------------------------------------------------
    // [시나리오 2] 자체 평균이 같아도 투표 수에 따라 점수가 달라진다
    // -------------------------------------------------------

    @Test
    @DisplayName("[시나리오2] 자체 평균 4.5로 동일해도 투표 수 많은 앨범이 더 높은 베이지안 점수를 가진다")
    void scenario2_sameSelfAvg_moreVotesMeansHigherBayesianScore() {
        double selfAvg = 4.5;

        // 앨범 C: 자체 평균 4.5, 5표
        BayesianResult albumC = calculator.calculate(
                BigDecimal.valueOf(selfAvg * 5),
                BigDecimal.valueOf(5),
                C
        );

        // 앨범 D: 자체 평균 4.5, 500표
        BayesianResult albumD = calculator.calculate(
                BigDecimal.valueOf(selfAvg * 500),
                BigDecimal.valueOf(500),
                C
        );

        // C(3.5) < 자체 평균(4.5) 이므로, 투표 수가 많을수록 자체 평균 방향으로 점수가 올라감
        assertThat(albumC.score()).isLessThan(albumD.score());

        // C는 5표로 esoteric, D는 500표로 mainstream
        assertThat(albumC.isEsoteric()).isTrue();
        assertThat(albumD.isEsoteric()).isFalse();
    }

    // -------------------------------------------------------
    // [시나리오 3] 가중치 높은 소수 = 가중치 낮은 다수
    // -------------------------------------------------------

    @Test
    @DisplayName("[시나리오3] weighted_count_sum이 같으면 투표 건수 무관하게 베이지안 점수가 동일해야 한다")
    void scenario3_sameWeightedCountSum_sameScore_regardlessOfRatingCount() {
        double targetScore = 5.0;

        // 앨범 E: weighting_score=0.1 유저 100명 → weighted_count_sum = 10
        double countE = 100 * 0.1; // 10.0
        BayesianResult albumE = calculator.calculate(
                BigDecimal.valueOf(targetScore * countE),
                BigDecimal.valueOf(countE),
                C
        );

        // 앨범 F: weighting_score=2.0 유저 5명 → weighted_count_sum = 10
        double countF = 5 * 2.0; // 10.0
        BayesianResult albumF = calculator.calculate(
                BigDecimal.valueOf(targetScore * countF),
                BigDecimal.valueOf(countF),
                C
        );

        // weighted_count_sum이 동일하므로 베이지안 점수도 동일해야 함
        assertThat(albumE.score()).isEqualByComparingTo(albumF.score());

        // 둘 다 weighted_count_sum = 10 < 50(M*K=50) → esoteric
        assertThat(albumE.isEsoteric()).isTrue();
        assertThat(albumF.isEsoteric()).isTrue();
    }
}
