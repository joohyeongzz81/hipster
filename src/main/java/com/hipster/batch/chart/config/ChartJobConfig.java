package com.hipster.batch.chart.config;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.step.ChartItemProcessor;
import com.hipster.batch.chart.step.ChartItemReaderConfig;
import com.hipster.batch.chart.step.ChartItemWriter;
import com.hipster.chart.config.ChartAlgorithmProperties;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.service.ChartPublishOrchestratorService;
import com.hipster.chart.publish.service.ChartPublishStateService;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static com.hipster.batch.chart.step.ChartItemProcessor.GLOBAL_AVG_KEY;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChartJobConfig {

    private static final int CHUNK_SIZE = 2_000;

    private final JobRepository jobRepository;
    private final EntityManagerFactory entityManagerFactory;
    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;
    private final ChartAlgorithmProperties chartAlgorithmProperties;
    private final ChartPublishProperties chartPublishProperties;
    private final ChartPublishOrchestratorService chartPublishOrchestratorService;
    private final ChartPublishStateService chartPublishStateService;
    private final ChartItemReaderConfig readerConfig;
    private final ChartItemProcessor processor;
    private final ChartItemWriter writer;
    private final SqlPagingQueryProviderFactoryBean chartQueryProvider;
    private final StringRedisTemplate redisTemplate;
    private final ChartLastUpdatedService chartLastUpdatedService;
    private final ChartElasticsearchIndexService chartElasticsearchIndexService;

    @Bean
    public Job chartUpdateJob() throws Exception {
        SimpleJobBuilder builder = new JobBuilder("chartUpdateJob", jobRepository)
                .start(globalAvgCalculationStep());

        if (chartPublishProperties.isEnabled()) {
            builder
                    .next(prepareCandidateVersionStep())
                    .next(chartScoreUpdateStep())
                    .next(elasticsearchSyncStep())
                    .next(cacheEvictionStep());
        } else {
            builder
                    .next(chartScoreUpdateStep())
                    .next(elasticsearchSyncStep())
                    .next(cacheEvictionStep());
        }

        return builder.build();
    }

    @Bean
    public Step globalAvgCalculationStep() {
        return new StepBuilder("globalAvgCalculationStep", jobRepository)
                .tasklet(globalAvgTasklet(), new JpaTransactionManager(entityManagerFactory))
                .build();
    }

    @Bean
    public Tasklet globalAvgTasklet() {
        return (contribution, chunkContext) -> {
            final BigDecimal globalAverage = releaseRatingSummaryRepository.calculateGlobalWeightedAverage()
                    .orElse(chartAlgorithmProperties.getGlobalAvgFallback());

            chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .put(GLOBAL_AVG_KEY, globalAverage);

            log.info("[CHART BATCH] Step 1 completed. globalAverage={}", globalAverage);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step prepareCandidateVersionStep() {
        return new StepBuilder("prepareCandidateVersionStep", jobRepository)
                .tasklet(prepareCandidateVersionTasklet(), new JpaTransactionManager(entityManagerFactory))
                .build();
    }

    @Bean
    public Tasklet prepareCandidateVersionTasklet() {
        return (contribution, chunkContext) -> {
            if (!chartPublishProperties.isEnabled()) {
                return RepeatStatus.FINISHED;
            }

            final var context = chartPublishOrchestratorService.generateCandidateVersion();
            final var executionContext = chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext();
            executionContext.putString(ChartPublishOrchestratorService.CANDIDATE_VERSION_KEY, context.version());
            executionContext.putString(
                    ChartPublishOrchestratorService.CANDIDATE_LOGICAL_AS_OF_KEY,
                    context.logicalAsOfAt().toString()
            );

            log.info(
                    "[CHART BATCH] Candidate version prepared. version={}, mysqlStage={}, esIndex={}",
                    context.version(),
                    context.candidateMysqlProjectionRef(),
                    context.candidateEsIndexRef()
            );
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step chartScoreUpdateStep() throws Exception {
        return new StepBuilder("chartScoreUpdateStep", jobRepository)
                .<ReleaseRatingSummary, ChartScoreDto>chunk(CHUNK_SIZE, new JpaTransactionManager(entityManagerFactory))
                .reader(readerConfig.chartItemReader(chartQueryProvider))
                .processor(processor)
                .writer(writer)
                .listener(entityManagerClearListener())
                .build();
    }

    @Bean
    public ChunkListener chartEntityManagerClearListener() {
        return new ChunkListener() {
            @Override
            public void afterChunk(final ChunkContext context) {
                entityManagerFactory.createEntityManager().clear();
                log.debug("[CHART BATCH] Chunk completed. EntityManager cleared");
            }
        };
    }

    private ChunkListener entityManagerClearListener() {
        return chartEntityManagerClearListener();
    }

    @Bean
    public Step elasticsearchSyncStep() {
        return new StepBuilder("elasticsearchSyncStep", jobRepository)
                .tasklet(elasticsearchSyncTasklet(), new JpaTransactionManager(entityManagerFactory))
                .build();
    }

    @Bean
    public Tasklet elasticsearchSyncTasklet() {
        return (contribution, chunkContext) -> {
            if (!chartPublishProperties.isEnabled()) {
                log.info("[CHART BATCH] Step 3 started. chart_scores -> ES legacy rebuild");
                chartElasticsearchIndexService.rebuildIndex();
                log.info("[CHART BATCH] Step 3 completed. legacy ES rebuild complete");
                return RepeatStatus.FINISHED;
            }

            final String candidateVersion = chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .getString(ChartPublishOrchestratorService.CANDIDATE_VERSION_KEY);

            log.info("[CHART BATCH] Step 3 started. chart_scores_stage -> ES candidate rebuild, version={}", candidateVersion);
            chartElasticsearchIndexService.rebuildCandidateIndex(candidateVersion, CHUNK_SIZE);
            log.info("[CHART BATCH] Step 3 completed. candidate ES rebuild complete, version={}", candidateVersion);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step cacheEvictionStep() {
        return new StepBuilder("cacheEvictionStep", jobRepository)
                .tasklet(cacheEvictionTasklet(), new JpaTransactionManager(entityManagerFactory))
                .build();
    }

    @Bean
    public Tasklet cacheEvictionTasklet() {
        return (contribution, chunkContext) -> {
            if (!chartPublishProperties.isEnabled()) {
                log.info("[CHART BATCH] Step 4 started. legacy Redis/meta publish");

                final Set<String> keys = redisTemplate.keys("chart:v1:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                    log.info("[CHART BATCH] Legacy chart cache evicted. deleted={}", keys.size());
                } else {
                    log.info("[CHART BATCH] No legacy chart cache keys found.");
                }

                chartLastUpdatedService.cacheLastUpdated(LocalDateTime.now());
                log.info("[CHART BATCH] Legacy lastUpdated metadata cached");
                return RepeatStatus.FINISHED;
            }

            final String candidateVersion = chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getExecutionContext()
                    .getString(ChartPublishOrchestratorService.CANDIDATE_VERSION_KEY);

            final var validation = chartPublishOrchestratorService.validateCandidateVersion(candidateVersion);
            if (!validation.blockingPassed()) {
                chartPublishStateService.markFailed("VALIDATION_FAILED", validation.summaryJson());
                throw new IllegalStateException("Chart publish validation failed for version=" + candidateVersion);
            }

            chartPublishOrchestratorService.publishVersion(candidateVersion);
            log.info("[CHART BATCH] Step 4 completed. candidate version published, version={}", candidateVersion);
            return RepeatStatus.FINISHED;
        };
    }
}
