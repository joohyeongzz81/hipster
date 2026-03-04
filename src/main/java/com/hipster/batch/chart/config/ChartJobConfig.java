package com.hipster.batch.chart.config;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.step.ChartItemProcessor;
import com.hipster.batch.chart.step.ChartItemReaderConfig;
import com.hipster.batch.chart.step.ChartItemWriter;
import com.hipster.chart.config.ChartAlgorithmProperties;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

import java.math.BigDecimal;

import static com.hipster.batch.chart.step.ChartItemProcessor.GLOBAL_AVG_KEY;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChartJobConfig {

    private static final int CHUNK_SIZE = 500;

    private final JobRepository jobRepository;
    private final EntityManagerFactory entityManagerFactory;
    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;
    private final ChartAlgorithmProperties chartAlgorithmProperties;
    private final ChartItemReaderConfig readerConfig;
    private final ChartItemProcessor processor;
    private final ChartItemWriter writer;

    /**
     * 매주 차트 점수 갱신 Job.
     * Step 1 (Tasklet): 글로벌 가중 평균 C 산출 → JobExecutionContext 저장
     * Step 2 (Chunk):   ReleaseRatingSummary 500건 단위 Bayesian 계산 → ChartScore UPSERT
     */
    @Bean
    public Job chartUpdateJob() {
        return new JobBuilder("chartUpdateJob", jobRepository)
                .start(globalAvgCalculationStep())
                .next(chartScoreUpdateStep())
                .build();
    }

    /**
     * Step 1 — 글로벌 가중 평균 C를 단 1회 산출하여 JobExecutionContext에 저장.
     * 멱등성: 같은 Job 재시작 시에도 C를 다시 계산하여 일관성을 보장.
     */
    @Bean
    public Step globalAvgCalculationStep() {
        return new StepBuilder("globalAvgCalculationStep", jobRepository)
                .tasklet(globalAvgTasklet(), new JpaTransactionManager(entityManagerFactory))
                .build();
    }

    @Bean
    public Tasklet globalAvgTasklet() {
        return (contribution, chunkContext) -> {
            final BigDecimal C = releaseRatingSummaryRepository.calculateGlobalWeightedAverage()
                    .orElse(chartAlgorithmProperties.getGlobalAvgFallback());

            chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .put(GLOBAL_AVG_KEY, C);

            log.info("[CHART BATCH] Step 1 완료. 글로벌 가중 평균 C = {}", C);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Step 2 — ReleaseRatingSummary를 500건 청크로 읽어 Bayesian 점수 계산 후 UPSERT.
     * 청크 단위 트랜잭션으로 부분 실패 시 해당 청크만 롤백.
     */
    @Bean
    public Step chartScoreUpdateStep() {
        return new StepBuilder("chartScoreUpdateStep", jobRepository)
                .<ReleaseRatingSummary, ChartScoreDto>chunk(CHUNK_SIZE, new JpaTransactionManager(entityManagerFactory))
                .reader(readerConfig.chartItemReader())
                .processor(processor)
                .writer(writer)
                .listener(entityManagerClearListener())
                .build();
    }

    /**
     * 청크 완료 후 EntityManager 1차 캐시를 비워 장기 배치의 메모리 누적을 방지.
     */
    @Bean
    public ChunkListener chartEntityManagerClearListener() {
        return new ChunkListener() {
            @Override
            public void afterChunk(final ChunkContext context) {
                entityManagerFactory.createEntityManager().clear();
                log.debug("[CHART BATCH] 청크 완료 후 EntityManager 1차 캐시 초기화");
            }
        };
    }

    private ChunkListener entityManagerClearListener() {
        return chartEntityManagerClearListener();
    }
}

