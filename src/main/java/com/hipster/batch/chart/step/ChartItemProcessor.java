package com.hipster.batch.chart.step;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.chart.algorithm.BayesianResult;
import com.hipster.chart.algorithm.BayesianScoreCalculator;
import com.hipster.chart.config.ChartAlgorithmProperties;
import com.hipster.rating.domain.ReleaseRatingSummary;
import org.springframework.lang.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ReleaseRatingSummary 단건을 받아 BayesianScore를 계산하고 ChartScoreDto로 변환.
 * 글로벌 평균 C는 Step 1(Tasklet)이 JobExecutionContext에 저장한 값을 @BeforeStep으로 주입받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartItemProcessor implements ItemProcessor<ReleaseRatingSummary, ChartScoreDto> {

    public static final String GLOBAL_AVG_KEY = "globalWeightedAverage";

    private final ChartAlgorithmProperties chartAlgorithmProperties;

    private BayesianScoreCalculator calculator;
    private BigDecimal C;

    @BeforeStep
    public void beforeStep(final StepExecution stepExecution) {
        this.C = (BigDecimal) stepExecution.getJobExecution()
                .getExecutionContext()
                .get(GLOBAL_AVG_KEY);

        if (this.C == null) {
            this.C = chartAlgorithmProperties.getGlobalAvgFallback();
            log.warn("[CHART BATCH] JobExecutionContext에 globalWeightedAverage 없음. 폴백값 사용: {}", this.C);
        }

        this.calculator = new BayesianScoreCalculator(
                chartAlgorithmProperties.getPriorWeightM(),
                chartAlgorithmProperties.getEsotericMultiplierK()
        );

        log.info("[CHART BATCH] Step 2 시작. 글로벌 가중 평균 C = {}", this.C);
    }

    @Override
    public ChartScoreDto process(final @NonNull ReleaseRatingSummary summary) {
        final BayesianResult result = calculator.calculate(
                summary.getWeightedScoreSum(),
                summary.getWeightedCountSum(),
                C
        );

        final double weightedAvgRating = summary.getWeightedCountSum().signum() > 0
                ? summary.getWeightedScoreSum()
                        .divide(summary.getWeightedCountSum(), 10, RoundingMode.HALF_UP)
                        .doubleValue()
                : 0.0;

        return new ChartScoreDto(
                summary.getReleaseId(),
                result.score().doubleValue(),
                weightedAvgRating,
                summary.getWeightedCountSum().doubleValue(),
                summary.getTotalRatingCount(),
                result.isEsoteric()
        );
    }
}

