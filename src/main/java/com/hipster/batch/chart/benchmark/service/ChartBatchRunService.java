package com.hipster.batch.chart.benchmark.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.batch.chart.benchmark.ChartProjectionWriteMode;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.publish.repository.ChartPublishStateRepository;
import com.hipster.chart.publish.service.ChartPublishedVersionService;
import com.hipster.chart.publish.service.ChartPublishStateService;
import com.hipster.chart.repository.ChartScoreIndexSourceQueryRepository.ChartScoreIndexSourceType;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChartBatchRunService {

    private static final String BENCHMARK_LIGHT_STAGE_TABLE = "chart_scores_stage_light_bench";
    private static final String RELEASES_TABLE = "releases";
    private static final String CHART_SCORES_TABLE = "chart_scores";
    private static final String RELEASE_RATING_SUMMARY_TABLE = "release_rating_summary";
    private static final DateTimeFormatter BENCHMARK_INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ChartPublishProperties chartPublishProperties;
    private final ChartPublishStateRepository chartPublishStateRepository;
    private final ChartPublishStateService chartPublishStateService;
    private final ChartPublishedVersionService chartPublishedVersionService;
    private final ChartLastUpdatedService chartLastUpdatedService;
    private final ChartBatchBenchmarkService chartBatchBenchmarkService;
    private final ChartElasticsearchIndexService chartElasticsearchIndexService;
    private final ElasticsearchClient elasticsearchClient;
    private final JobLauncher jobLauncher;
    private final Job chartUpdateJob;

    public ChartBatchRunService(final JdbcTemplate jdbcTemplate,
                                final StringRedisTemplate redisTemplate,
                                final ChartPublishProperties chartPublishProperties,
                                final ChartPublishStateRepository chartPublishStateRepository,
                                final ChartPublishStateService chartPublishStateService,
                                final ChartPublishedVersionService chartPublishedVersionService,
                                final ChartLastUpdatedService chartLastUpdatedService,
                                final ChartBatchBenchmarkService chartBatchBenchmarkService,
                                final ChartElasticsearchIndexService chartElasticsearchIndexService,
                                final ElasticsearchClient elasticsearchClient,
                                final JobLauncher jobLauncher,
                                @Qualifier("chartUpdateJob") final Job chartUpdateJob) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.chartPublishProperties = chartPublishProperties;
        this.chartPublishStateRepository = chartPublishStateRepository;
        this.chartPublishStateService = chartPublishStateService;
        this.chartPublishedVersionService = chartPublishedVersionService;
        this.chartLastUpdatedService = chartLastUpdatedService;
        this.chartBatchBenchmarkService = chartBatchBenchmarkService;
        this.chartElasticsearchIndexService = chartElasticsearchIndexService;
        this.elasticsearchClient = elasticsearchClient;
        this.jobLauncher = jobLauncher;
        this.chartUpdateJob = chartUpdateJob;
    }

    public PrecheckSnapshot collectPrecheckSnapshot() {
        final LocalDateTime capturedAt = LocalDateTime.now();
        return new PrecheckSnapshot(
                capturedAt,
                chartPublishProperties.isEnabled(),
                "UP",
                readRedisHealth(),
                readElasticsearchHealth(),
                collectRowCounts(),
                readPublishStateSnapshot(),
                readRedisSnapshot(),
                readEsAliasSnapshot()
        );
    }

    public PublishStateSnapshot bootstrapLegacyState() {
        final ChartPublishState existingState = chartPublishStateRepository.findById(chartPublishProperties.getChartName())
                .orElse(null);

        if (existingState != null && existingState.getCurrentVersion() != null && !existingState.getCurrentVersion().isBlank()) {
            if (existingState.getEsIndexRef() != null && chartElasticsearchIndexService.resolvePublishedAliasTarget() == null) {
                chartElasticsearchIndexService.rollbackAliasToIndex(existingState.getEsIndexRef());
            }
            chartPublishedVersionService.cachePublishedVersion(existingState.getCurrentVersion());
            if (existingState.getLogicalAsOfAt() != null) {
                chartLastUpdatedService.cacheLastUpdated(existingState.getLogicalAsOfAt());
            }
            return toPublishStateSnapshot(existingState);
        }

        final LocalDateTime logicalAsOfAt = resolveLegacyLogicalAsOfAt();
        final String publishedIndexRef = chartElasticsearchIndexService.resolvePublishedIndexName();
        chartElasticsearchIndexService.rollbackAliasToIndex(publishedIndexRef);

        final ChartPublishState bootstrapped = chartPublishStateService.bootstrapPublishedState(
                "legacy",
                CHART_SCORES_TABLE,
                publishedIndexRef,
                logicalAsOfAt,
                LocalDateTime.now()
        );
        chartPublishedVersionService.cachePublishedVersion(bootstrapped.getCurrentVersion());
        chartLastUpdatedService.cacheLastUpdated(bootstrapped.getLogicalAsOfAt());
        return toPublishStateSnapshot(bootstrapped);
    }

    public FullBatchBenchmarkRunSummary runFullBatchBenchmark(final ChartProjectionWriteMode writeMode,
                                                              final int projectionChunkSize,
                                                              final int esBatchSize,
                                                              final String benchmarkIndexName) throws Exception {
        final LocalDateTime jobStartedAt = LocalDateTime.now();
        final List<StepRunSummary> stepTimings = new ArrayList<>();
        final String resolvedBenchmarkIndexName =
                benchmarkIndexName != null && !benchmarkIndexName.isBlank()
                        ? benchmarkIndexName
                        : chartElasticsearchIndexService.buildBenchmarkIndexName(jobStartedAt.format(BENCHMARK_INDEX_SUFFIX));

        String failureStep = null;
        String failureMessage = null;
        ChartBatchBenchmarkService.ProjectionSampleResponse projection = null;
        ChartElasticsearchIndexService.ElasticsearchIndexSample elasticsearch = null;

        try {
            final long totalProjectionRows = safeCount(RELEASE_RATING_SUMMARY_TABLE);
            final int totalChunks = projectionChunkSize <= 0
                    ? 0
                    : (int) Math.ceil((double) totalProjectionRows / projectionChunkSize);

            final LocalDateTime projectionStartedAt = LocalDateTime.now();
            projection = chartBatchBenchmarkService.benchmarkProjectionSample(
                    0,
                    totalChunks,
                    projectionChunkSize,
                    writeMode
            );
            final LocalDateTime projectionEndedAt = LocalDateTime.now();
            stepTimings.add(new StepRunSummary(
                    "chartScoreUpdateStep",
                    "COMPLETED",
                    projectionStartedAt,
                    projectionEndedAt,
                    millisBetween(projectionStartedAt, projectionEndedAt),
                    projectionMetrics(projection),
                    null
            ));

            final LocalDateTime elasticsearchStartedAt = LocalDateTime.now();
            elasticsearch = chartElasticsearchIndexService.benchmarkFullRun(
                    resolveBenchmarkSourceType(writeMode),
                    esBatchSize,
                    resolvedBenchmarkIndexName
            );
            final LocalDateTime elasticsearchEndedAt = LocalDateTime.now();
            stepTimings.add(new StepRunSummary(
                    "elasticsearchSyncStep",
                    "COMPLETED",
                    elasticsearchStartedAt,
                    elasticsearchEndedAt,
                    millisBetween(elasticsearchStartedAt, elasticsearchEndedAt),
                    elasticsearchMetrics(elasticsearch, resolvedBenchmarkIndexName, writeMode),
                    null
            ));

            final LocalDateTime cacheStartedAt = LocalDateTime.now();
            final SyntheticCacheEvictionSummary cacheEviction = measureSyntheticCacheEviction();
            final LocalDateTime cacheEndedAt = LocalDateTime.now();
            stepTimings.add(new StepRunSummary(
                    "cacheEvictionStep",
                    "COMPLETED",
                    cacheStartedAt,
                    cacheEndedAt,
                    millisBetween(cacheStartedAt, cacheEndedAt),
                    cacheEvictionMetrics(cacheEviction),
                    null
            ));
        } catch (Exception e) {
            failureStep = stepTimings.isEmpty() ? "chartScoreUpdateStep" : inferFailureStep(stepTimings);
            failureMessage = e.getMessage();
            stepTimings.add(failedStepSummary(failureStep, failureMessage));
        }

        final LocalDateTime jobEndedAt = LocalDateTime.now();
        final long totalBatchMillis = millisBetween(jobStartedAt, jobEndedAt);
        final long stepExecutionTotalMillis = stepTimings.stream()
                .mapToLong(StepRunSummary::executionMillis)
                .sum();

        return new FullBatchBenchmarkRunSummary(
                "FULL_BATCH_BENCHMARK",
                jobStartedAt,
                jobEndedAt,
                totalBatchMillis,
                writeMode.name(),
                projectionChunkSize,
                esBatchSize,
                resolvedBenchmarkIndexName,
                null,
                stepTimings,
                stepExecutionTotalMillis,
                Math.max(totalBatchMillis - stepExecutionTotalMillis, 0L),
                failureStep,
                failureMessage,
                collectRowCounts(),
                projection,
                elasticsearch,
                safeCountDocuments(resolvedBenchmarkIndexName)
        );
    }

    public PublishJobRunSummary runPublishJob() throws Exception {
        final JobExecution execution = jobLauncher.run(
                chartUpdateJob,
                new JobParametersBuilder()
                        .addLong("ts", System.currentTimeMillis())
                        .toJobParameters()
        );

        final List<StepRunSummary> stepTimings = execution.getStepExecutions().stream()
                .sorted(Comparator.comparing(
                        StepExecution::getStartTime,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .map(this::toStepRunSummary)
                .toList();

        final LocalDateTime jobStartedAt = execution.getStartTime();
        final LocalDateTime jobEndedAt = execution.getEndTime();
        final long totalBatchMillis = millisBetween(jobStartedAt, jobEndedAt);
        final long stepExecutionTotalMillis = stepTimings.stream()
                .mapToLong(StepRunSummary::executionMillis)
                .sum();
        final String failureMessage = execution.getAllFailureExceptions().isEmpty()
                ? null
                : execution.getAllFailureExceptions().get(0).getMessage();

        return new PublishJobRunSummary(
                "CH8_PUBLISH_JOB",
                execution.getId(),
                execution.getJobInstance().getJobName(),
                execution.getStatus().name(),
                execution.getExitStatus().getExitCode(),
                execution.getExitStatus().getExitDescription(),
                jobStartedAt,
                jobEndedAt,
                totalBatchMillis,
                stepTimings,
                stepExecutionTotalMillis,
                Math.max(totalBatchMillis - stepExecutionTotalMillis, 0L),
                findFailureStep(stepTimings, execution.getStatus().name()),
                failureMessage,
                readPublishStateSnapshot(),
                readRedisSnapshot(),
                readEsAliasSnapshot()
        );
    }

    private StepRunSummary toStepRunSummary(final StepExecution stepExecution) {
        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("readCount", stepExecution.getReadCount());
        metrics.put("writeCount", stepExecution.getWriteCount());
        metrics.put("filterCount", stepExecution.getFilterCount());
        metrics.put("commitCount", stepExecution.getCommitCount());
        metrics.put("rollbackCount", stepExecution.getRollbackCount());
        metrics.put("readSkipCount", stepExecution.getReadSkipCount());
        metrics.put("processSkipCount", stepExecution.getProcessSkipCount());
        metrics.put("writeSkipCount", stepExecution.getWriteSkipCount());
        metrics.put("skipCount", stepExecution.getSkipCount());

        return new StepRunSummary(
                stepExecution.getStepName(),
                stepExecution.getStatus().name(),
                stepExecution.getStartTime(),
                stepExecution.getEndTime(),
                millisBetween(stepExecution.getStartTime(), stepExecution.getEndTime()),
                metrics,
                stepExecution.getExitStatus().getExitDescription()
        );
    }

    private StepRunSummary failedStepSummary(final String stepName, final String failureMessage) {
        final LocalDateTime now = LocalDateTime.now();
        return new StepRunSummary(
                stepName,
                "FAILED",
                now,
                now,
                0L,
                Map.of(),
                failureMessage
        );
    }

    private String inferFailureStep(final List<StepRunSummary> completedSteps) {
        final boolean projectionCompleted = completedSteps.stream()
                .anyMatch(step -> "chartScoreUpdateStep".equals(step.stepName()));
        final boolean esCompleted = completedSteps.stream()
                .anyMatch(step -> "elasticsearchSyncStep".equals(step.stepName()));

        if (!projectionCompleted) {
            return "chartScoreUpdateStep";
        }
        if (!esCompleted) {
            return "elasticsearchSyncStep";
        }
        return "cacheEvictionStep";
    }

    private String findFailureStep(final List<StepRunSummary> stepTimings, final String jobStatus) {
        if ("COMPLETED".equals(jobStatus)) {
            return null;
        }

        return stepTimings.stream()
                .filter(step -> !"COMPLETED".equals(step.status()))
                .map(StepRunSummary::stepName)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> projectionMetrics(final ChartBatchBenchmarkService.ProjectionSampleResponse projection) {
        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalSummaryRows", projection.totalSummaryRows());
        metrics.put("totalChunks", projection.totalChunks());
        metrics.put("sampledRows", projection.sampledRows());
        metrics.put("fetchMillis", projection.totalFetchMillis());
        metrics.put("processMillis", projection.totalProcessMillis());
        metrics.put("writeMillis", projection.totalWriteMillis());
        metrics.put("metadataFetchMillis", projection.totalMetadataFetchMillis());
        metrics.put("serializationMillis", projection.totalSerializationMillis());
        metrics.put("upsertMillis", projection.totalUpsertMillis());
        metrics.put("estimatedFullRunMinutes", projection.estimatedFullRunMinutes());
        return metrics;
    }

    private Map<String, Object> elasticsearchMetrics(final ChartElasticsearchIndexService.ElasticsearchIndexSample elasticsearch,
                                                     final String benchmarkIndexName,
                                                     final ChartProjectionWriteMode writeMode) {
        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sourceWriteMode", writeMode.name());
        metrics.put("targetIndexName", benchmarkIndexName);
        metrics.put("totalChartScores", elasticsearch.totalChartScores());
        metrics.put("totalPages", elasticsearch.totalPages());
        metrics.put("indexedDocuments", elasticsearch.indexedDocuments());
        metrics.put("fetchMillis", elasticsearch.totalFetchMillis());
        metrics.put("indexMillis", elasticsearch.totalIndexMillis());
        metrics.put("refreshMillis", elasticsearch.refreshMillis());
        metrics.put("estimatedFullRunMinutes", elasticsearch.estimatedFullRunMinutes());
        return metrics;
    }

    private Map<String, Object> cacheEvictionMetrics(final SyntheticCacheEvictionSummary cacheEviction) {
        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("keyPattern", cacheEviction.keyPattern());
        metrics.put("deletedKeys", cacheEviction.deletedKeys());
        metrics.put("synthetic", true);
        return metrics;
    }

    private SyntheticCacheEvictionSummary measureSyntheticCacheEviction() {
        final String keyPattern = "chart:v1:benchmark:*";
        final LocalDateTime startedAt = LocalDateTime.now();
        final Set<String> keys = redisTemplate.keys(keyPattern);
        final int deletedKeys;
        if (keys == null || keys.isEmpty()) {
            deletedKeys = 0;
        } else {
            redisTemplate.delete(keys);
            deletedKeys = keys.size();
        }
        final LocalDateTime endedAt = LocalDateTime.now();
        return new SyntheticCacheEvictionSummary(
                keyPattern,
                deletedKeys,
                startedAt,
                endedAt,
                millisBetween(startedAt, endedAt)
        );
    }

    private ChartScoreIndexSourceType resolveBenchmarkSourceType(final ChartProjectionWriteMode writeMode) {
        return switch (writeMode) {
            case LIGHT_STAGE_INSERT -> ChartScoreIndexSourceType.BENCHMARK_LIGHT_STAGE;
            case PUBLISH_STAGE -> ChartScoreIndexSourceType.STAGE;
            case UPSERT -> ChartScoreIndexSourceType.PUBLISHED;
            case STAGING_INSERT -> throw new IllegalArgumentException("STAGING_INSERT full benchmark source is not supported.");
        };
    }

    private RowCountSnapshot collectRowCounts() {
        return new RowCountSnapshot(
                safeCount(RELEASES_TABLE),
                safeCount(CHART_SCORES_TABLE),
                safeCount(RELEASE_RATING_SUMMARY_TABLE),
                safeCountIfExists(chartPublishProperties.getStageTableName()),
                safeCountIfExists(chartPublishProperties.getPreviousTableName()),
                safeCountIfExists(BENCHMARK_LIGHT_STAGE_TABLE),
                safeMultiGenreCount()
        );
    }

    private LocalDateTime resolveLegacyLogicalAsOfAt() {
        final LocalDateTime fromChartScores = jdbcTemplate.query(
                "SELECT MAX(last_updated) FROM chart_scores",
                rs -> rs.next() ? rs.getTimestamp(1) != null ? rs.getTimestamp(1).toLocalDateTime() : null : null
        );
        if (fromChartScores != null) {
            return fromChartScores;
        }

        final LocalDateTime fromSummary = jdbcTemplate.query(
                "SELECT COALESCE(MAX(batch_synced_at), MAX(updated_at)) FROM release_rating_summary",
                rs -> rs.next() ? rs.getTimestamp(1) != null ? rs.getTimestamp(1).toLocalDateTime() : null : null
        );
        return fromSummary != null ? fromSummary : LocalDateTime.now();
    }

    private PublishStateSnapshot readPublishStateSnapshot() {
        return chartPublishStateRepository.findById(chartPublishProperties.getChartName())
                .map(this::toPublishStateSnapshot)
                .orElse(null);
    }

    private PublishStateSnapshot toPublishStateSnapshot(final ChartPublishState state) {
        return new PublishStateSnapshot(
                state.getChartName(),
                state.getStatus().name(),
                state.getCurrentVersion(),
                state.getPreviousVersion(),
                state.getCandidateVersion(),
                state.getMysqlProjectionRef(),
                state.getPreviousMysqlProjectionRef(),
                state.getCandidateMysqlProjectionRef(),
                state.getEsIndexRef(),
                state.getPreviousEsIndexRef(),
                state.getCandidateEsIndexRef(),
                state.getLogicalAsOfAt(),
                state.getPreviousLogicalAsOfAt(),
                state.getCandidateLogicalAsOfAt(),
                state.getPublishedAt(),
                state.getLastValidationStatus().name(),
                state.getLastErrorCode(),
                state.getLastErrorMessage(),
                state.getUpdatedAt()
        );
    }

    private RedisSnapshot readRedisSnapshot() {
        return new RedisSnapshot(
                readRedisValue(chartPublishProperties.getPublishedVersionCacheKey()),
                readRedisValue("chart-meta:last-updated:v1")
        );
    }

    private EsAliasSnapshot readEsAliasSnapshot() {
        final String aliasName = chartElasticsearchIndexService.resolvePublishedAliasName();
        final String aliasTarget = chartElasticsearchIndexService.resolvePublishedAliasTarget();
        return new EsAliasSnapshot(
                aliasName,
                aliasTarget,
                chartElasticsearchIndexService.resolvePublishedIndexName()
        );
    }

    private String readRedisHealth() {
        try {
            return redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private String readElasticsearchHealth() {
        try {
            return String.valueOf(elasticsearchClient.cluster().health().status());
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private String readRedisValue(final String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private long safeCount(final String tableName) {
        final Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    private long safeCountIfExists(final String tableName) {
        if (!tableExists(tableName)) {
            return 0L;
        }
        return safeCount(tableName);
    }

    private long safeMultiGenreCount() {
        if (!tableExists(CHART_SCORES_TABLE)) {
            return 0L;
        }

        final Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chart_scores WHERE JSON_LENGTH(genre_ids) >= 2",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private boolean tableExists(final String tableName) {
        final Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName
        );
        return exists != null && exists > 0;
    }

    private long safeCountDocuments(final String indexName) {
        try {
            return elasticsearchClient.count(request -> request.index(indexName)).count();
        } catch (IOException e) {
            return -1L;
        }
    }

    private long millisBetween(final LocalDateTime startedAt, final LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null) {
            return 0L;
        }
        return Duration.between(startedAt, endedAt).toMillis();
    }

    public record PrecheckSnapshot(
            LocalDateTime capturedAt,
            boolean publishEnabled,
            String appBenchmarkHealth,
            String redisHealth,
            String elasticsearchHealth,
            RowCountSnapshot rowCounts,
            PublishStateSnapshot publishState,
            RedisSnapshot redis,
            EsAliasSnapshot esAlias
    ) {
    }

    public record RowCountSnapshot(
            long releases,
            long chartScores,
            long releaseRatingSummary,
            long chartScoresStage,
            long chartScoresPrev,
            long chartScoresLightStageBench,
            long multiGenreRows
    ) {
    }

    public record PublishStateSnapshot(
            String chartName,
            String status,
            String currentVersion,
            String previousVersion,
            String candidateVersion,
            String mysqlProjectionRef,
            String previousMysqlProjectionRef,
            String candidateMysqlProjectionRef,
            String esIndexRef,
            String previousEsIndexRef,
            String candidateEsIndexRef,
            LocalDateTime logicalAsOfAt,
            LocalDateTime previousLogicalAsOfAt,
            LocalDateTime candidateLogicalAsOfAt,
            LocalDateTime publishedAt,
            String lastValidationStatus,
            String lastErrorCode,
            String lastErrorMessage,
            LocalDateTime updatedAt
    ) {
    }

    public record RedisSnapshot(
            String publishedVersion,
            String lastUpdated
    ) {
    }

    public record EsAliasSnapshot(
            String aliasName,
            String aliasTarget,
            String publishedIndexName
    ) {
    }

    public record StepRunSummary(
            String stepName,
            String status,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            long executionMillis,
            Map<String, Object> metrics,
            String errorMessage
    ) {
    }

    public record FullBatchBenchmarkRunSummary(
            String runType,
            LocalDateTime jobStartedAt,
            LocalDateTime jobEndedAt,
            long totalBatchMillis,
            String writeMode,
            int projectionChunkSize,
            int esBatchSize,
            String benchmarkIndexName,
            Long prepareCandidateVersionStepMillis,
            List<StepRunSummary> stepTimings,
            long stepExecutionTotalMillis,
            long wallClockDiffMillis,
            String failureStep,
            String failureMessage,
            RowCountSnapshot rowCountsAfterRun,
            ChartBatchBenchmarkService.ProjectionSampleResponse projection,
            ChartElasticsearchIndexService.ElasticsearchIndexSample elasticsearch,
            long benchmarkIndexDocumentCount
    ) {
    }

    public record PublishJobRunSummary(
            String runType,
            Long jobExecutionId,
            String jobName,
            String status,
            String exitCode,
            String exitDescription,
            LocalDateTime jobStartedAt,
            LocalDateTime jobEndedAt,
            long totalBatchMillis,
            List<StepRunSummary> stepTimings,
            long stepExecutionTotalMillis,
            long wallClockDiffMillis,
            String failureStep,
            String failureMessage,
            PublishStateSnapshot publishState,
            RedisSnapshot redis,
            EsAliasSnapshot esAlias
    ) {
    }

    private record SyntheticCacheEvictionSummary(
            String keyPattern,
            int deletedKeys,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            long executionMillis
    ) {
    }
}
