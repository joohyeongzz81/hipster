package com.hipster.batch.chart.benchmark.service;

import com.hipster.batch.chart.benchmark.ChartProjectionWriteMode;
import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.batch.chart.step.ChartItemWriter;
import com.hipster.chart.algorithm.BayesianResult;
import com.hipster.chart.algorithm.BayesianScoreCalculator;
import com.hipster.chart.config.ChartAlgorithmProperties;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartBatchBenchmarkService {

    private static final int DEFAULT_CHUNK_SIZE = 2_000;

    private final JdbcTemplate jdbcTemplate;
    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;
    private final ChartAlgorithmProperties chartAlgorithmProperties;
    private final ChartItemWriter chartItemWriter;
    private final ChartScoreQueryRepository chartScoreQueryRepository;
    private final ChartElasticsearchIndexService chartElasticsearchIndexService;
    private final StringRedisTemplate redisTemplate;
    private final ChartLastUpdatedService chartLastUpdatedService;

    @Transactional
    public ProjectionSampleResponse benchmarkProjectionSample(final int startChunk,
                                                             final int chunkCount,
                                                             final int chunkSize,
                                                             final ChartProjectionWriteMode writeMode) throws Exception {
        final int safeChunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        final long totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM release_rating_summary",
                Long.class
        );
        final long totalChunks = totalRows == 0 ? 0 : (long) Math.ceil((double) totalRows / safeChunkSize);
        final BigDecimal globalAverage = releaseRatingSummaryRepository.calculateGlobalWeightedAverage()
                .orElse(chartAlgorithmProperties.getGlobalAvgFallback());
        final BayesianScoreCalculator calculator = new BayesianScoreCalculator(
                chartAlgorithmProperties.getPriorWeightM(),
                chartAlgorithmProperties.getEsotericMultiplierK()
        );

        if (writeMode == ChartProjectionWriteMode.PUBLISH_STAGE) {
            chartScoreQueryRepository.preparePublishStageTable();
        } else if (writeMode == ChartProjectionWriteMode.LIGHT_STAGE_INSERT) {
            chartScoreQueryRepository.prepareBenchmarkLightStageTable();
        } else if (writeMode == ChartProjectionWriteMode.STAGING_INSERT) {
            chartScoreQueryRepository.prepareBenchmarkStageTable();
        }

        final long startAfterId = resolveStartAfterId(startChunk, safeChunkSize);
        long cursorId = startAfterId;
        long sampledRows = 0L;
        final List<ProjectionChunkTiming> chunks = new ArrayList<>();

        for (int i = 0; i < chunkCount; i++) {
            final long fetchStart = System.nanoTime();
            final List<SummaryRow> summaryRows = jdbcTemplate.query(
                    """
                    SELECT id, release_id, total_rating_count, average_score, weighted_score_sum, weighted_count_sum
                    FROM release_rating_summary
                    WHERE id > ?
                    ORDER BY id ASC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        final ReleaseRatingSummary summary = new ReleaseRatingSummary(rs.getLong("release_id"));
                        summary.recalculate(
                                rs.getLong("total_rating_count"),
                                rs.getDouble("average_score"),
                                rs.getBigDecimal("weighted_score_sum"),
                                rs.getBigDecimal("weighted_count_sum")
                        );
                        return new SummaryRow(rs.getLong("id"), summary);
                    },
                    cursorId,
                    safeChunkSize
            );
            final long fetchMillis = Duration.ofNanos(System.nanoTime() - fetchStart).toMillis();

            if (summaryRows.isEmpty()) {
                break;
            }

            final long processStart = System.nanoTime();
            final List<ChartScoreDto> dtos = summaryRows.stream()
                    .map(row -> toChartScoreDto(row.summary(), globalAverage, calculator))
                    .toList();
            final long processMillis = Duration.ofNanos(System.nanoTime() - processStart).toMillis();

            final long writeStart = System.nanoTime();
            final ChartItemWriter.WriteBreakdown writeBreakdown =
                    chartItemWriter.writeWithBreakdown(new Chunk<>(dtos), writeMode);
            final long writeMillis = Duration.ofNanos(System.nanoTime() - writeStart).toMillis();

            cursorId = summaryRows.get(summaryRows.size() - 1).id();
            sampledRows += summaryRows.size();
            chunks.add(new ProjectionChunkTiming(
                    startChunk + i,
                    summaryRows.size(),
                    fetchMillis,
                    processMillis,
                    writeMillis,
                    writeBreakdown.metadataFetchMillis(),
                    writeBreakdown.serializationMillis(),
                    writeBreakdown.upsertMillis()
            ));

            final int sampledChunkCount = chunks.size();
            final double localProgressPercent = chunkCount == 0 ? 100.0 : ((double) sampledChunkCount / chunkCount) * 100.0;
            final double globalProgressPercent = totalChunks == 0
                    ? 100.0
                    : ((double) (startChunk + sampledChunkCount) / totalChunks) * 100.0;
            log.info(
                    "[CHART BATCH][PROJECTION SAMPLE] progress local={}/{} ({}%), globalChunk={}/{} ({}%), sampledRows={}, chunkTotal={}ms, metadata={}ms, dbWrite={}ms, mode={}",
                    sampledChunkCount,
                    chunkCount,
                    String.format("%.1f", localProgressPercent),
                    startChunk + sampledChunkCount,
                    totalChunks,
                    String.format("%.1f", globalProgressPercent),
                    sampledRows,
                    fetchMillis + processMillis + writeMillis,
                    writeBreakdown.metadataFetchMillis(),
                    writeBreakdown.upsertMillis(),
                    writeMode
            );
        }

        return new ProjectionSampleResponse(
                totalRows,
                totalChunks,
                safeChunkSize,
                startChunk,
                writeMode,
                chunks,
                sampledRows,
                globalAverage
        );
    }

    @Transactional(readOnly = true)
    public ChartElasticsearchIndexService.ElasticsearchIndexSample benchmarkElasticsearchSample(final int startPage,
                                                                                                 final int pageCount,
                                                                                                 final int batchSize) {
        return chartElasticsearchIndexService.benchmarkSample(startPage, pageCount, batchSize);
    }

    public PublishSampleResponse benchmarkPublishSample() {
        final long cacheStart = System.nanoTime();
        final Set<String> keys = redisTemplate.keys("chart:v1:*");
        final int deletedKeys;
        if (keys == null || keys.isEmpty()) {
            deletedKeys = 0;
        } else {
            redisTemplate.delete(keys);
            deletedKeys = keys.size();
        }
        final long cacheEvictionMillis = Duration.ofNanos(System.nanoTime() - cacheStart).toMillis();

        final long metaStart = System.nanoTime();
        chartLastUpdatedService.cacheLastUpdated(LocalDateTime.now());
        final long metadataMillis = Duration.ofNanos(System.nanoTime() - metaStart).toMillis();

        return new PublishSampleResponse(
                deletedKeys,
                cacheEvictionMillis,
                metadataMillis
        );
    }

    @Transactional
    public PipelineEstimateResponse benchmarkPipelineEstimate(final int sampleChunkCount,
                                                             final int chunkSize,
                                                             final ChartProjectionWriteMode writeMode,
                                                             final int esSamplePageCount,
                                                             final int esBatchSize) throws Exception {
        final int safeChunkSampleCount = sampleChunkCount > 0 ? sampleChunkCount : 2;
        final int safeChunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        final int safeEsSamplePageCount = esSamplePageCount > 0 ? esSamplePageCount : 2;
        final int safeEsBatchSize = esBatchSize > 0 ? esBatchSize : DEFAULT_CHUNK_SIZE;

        final long totalProjectionRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM release_rating_summary", Long.class);
        final long totalProjectionChunks = totalProjectionRows == 0 ? 0 : (long) Math.ceil((double) totalProjectionRows / safeChunkSize);
        final List<Integer> projectionStartChunks = selectSampleStarts(totalProjectionChunks, safeChunkSampleCount);

        final List<ProjectionSampleResponse> projectionSamples = new ArrayList<>();
        for (int i = 0; i < projectionStartChunks.size(); i++) {
            final int start = projectionStartChunks.get(i);
            log.info(
                    "[CHART BATCH][ESTIMATE] projection segment {}/{} started. startChunk={}, chunkCount={}, chunkSize={}, mode={}",
                    i + 1,
                    projectionStartChunks.size(),
                    start,
                    safeChunkSampleCount,
                    safeChunkSize,
                    writeMode
            );
            projectionSamples.add(benchmarkProjectionSample(start, safeChunkSampleCount, safeChunkSize, writeMode));
        }

        final long totalEsRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chart_scores", Long.class);
        final long totalEsPages = totalEsRows == 0 ? 0 : (long) Math.ceil((double) totalEsRows / safeEsBatchSize);
        final List<Integer> esStartPages = selectSampleStarts(totalEsPages, safeEsSamplePageCount);

        final List<ChartElasticsearchIndexService.ElasticsearchIndexSample> esSamples = new ArrayList<>();
        for (int i = 0; i < esStartPages.size(); i++) {
            final int start = esStartPages.get(i);
            log.info(
                    "[CHART BATCH][ESTIMATE] es segment {}/{} started. startPage={}, pageCount={}, batchSize={}",
                    i + 1,
                    esStartPages.size(),
                    start,
                    safeEsSamplePageCount,
                    safeEsBatchSize
            );
            esSamples.add(benchmarkElasticsearchSample(start, safeEsSamplePageCount, safeEsBatchSize));
        }

        log.info("[CHART BATCH][ESTIMATE] publish sample started.");
        final PublishSampleResponse publishSample = benchmarkPublishSample();

        final double projectionEstimatedMinutes = projectionSamples.stream()
                .mapToDouble(ProjectionSampleResponse::estimatedFullRunMinutes)
                .average()
                .orElse(0.0);
        final double esEstimatedMinutes = esSamples.stream()
                .mapToDouble(ChartElasticsearchIndexService.ElasticsearchIndexSample::estimatedFullRunMinutes)
                .average()
                .orElse(0.0);
        final double publishEstimatedMinutes = publishSample.totalMillis() / 1000.0 / 60.0;

        return new PipelineEstimateResponse(
                projectionStartChunks,
                projectionSamples,
                esStartPages,
                esSamples,
                publishSample,
                projectionEstimatedMinutes,
                esEstimatedMinutes,
                publishEstimatedMinutes
        );
    }

    private List<Integer> selectSampleStarts(final long totalUnits, final int sampleWindowSize) {
        if (totalUnits <= 0) {
            return List.of(0);
        }

        final int early = 0;
        final int middle = (int) Math.max(0, (totalUnits / 2) - (sampleWindowSize / 2));
        final int late = (int) Math.max(0, totalUnits - sampleWindowSize);

        return List.of(early, middle, late).stream()
                .distinct()
                .sorted()
                .toList();
    }

    private long resolveStartAfterId(final int startChunk, final int chunkSize) {
        if (startChunk <= 0) {
            return 0L;
        }

        final long offset = ((long) startChunk * chunkSize) - 1L;
        final Long startId = jdbcTemplate.query(
                "SELECT id FROM release_rating_summary ORDER BY id ASC LIMIT 1 OFFSET ?",
                rs -> rs.next() ? rs.getLong(1) : 0L,
                offset
        );
        return startId == null ? 0L : startId;
    }

    private ChartScoreDto toChartScoreDto(final ReleaseRatingSummary summary,
                                          final BigDecimal globalAverage,
                                          final BayesianScoreCalculator calculator) {
        final BayesianResult result = calculator.calculate(
                summary.getWeightedScoreSum(),
                summary.getWeightedCountSum(),
                globalAverage
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
                result.isEsoteric(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private record SummaryRow(long id, ReleaseRatingSummary summary) {
    }

    public record ProjectionSampleResponse(
            long totalSummaryRows,
            long totalChunks,
            int chunkSize,
            int startChunk,
            ChartProjectionWriteMode writeMode,
            List<ProjectionChunkTiming> chunkTimings,
            long sampledRows,
            BigDecimal globalAverage
    ) {
        public long totalFetchMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::fetchMillis).sum();
        }

        public long totalProcessMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::processMillis).sum();
        }

        public long totalWriteMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::writeMillis).sum();
        }

        public long totalMetadataFetchMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::metadataFetchMillis).sum();
        }

        public long totalSerializationMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::serializationMillis).sum();
        }

        public long totalUpsertMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::upsertMillis).sum();
        }

        public long totalMillis() {
            return chunkTimings.stream().mapToLong(ProjectionChunkTiming::totalMillis).sum();
        }

        public double avgChunkMillis() {
            return chunkTimings.isEmpty() ? 0.0 : (double) totalMillis() / chunkTimings.size();
        }

        public double estimatedFullRunMinutes() {
            if (chunkTimings.isEmpty()) {
                return 0.0;
            }
            return (avgChunkMillis() * totalChunks) / 1000.0 / 60.0;
        }
    }

    public record ProjectionChunkTiming(
            int chunkIndex,
            int rowCount,
            long fetchMillis,
            long processMillis,
            long writeMillis,
            long metadataFetchMillis,
            long serializationMillis,
            long upsertMillis
    ) {
        public long totalMillis() {
            return fetchMillis + processMillis + writeMillis;
        }
    }

    public record PublishSampleResponse(
            int deletedKeys,
            long cacheEvictionMillis,
            long metadataMillis
    ) {
        public long totalMillis() {
            return cacheEvictionMillis + metadataMillis;
        }
    }

    public record PipelineEstimateResponse(
            List<Integer> projectionStartChunks,
            List<ProjectionSampleResponse> projectionSamples,
            List<Integer> esStartPages,
            List<ChartElasticsearchIndexService.ElasticsearchIndexSample> esSamples,
            PublishSampleResponse publishSample,
            double projectionEstimatedMinutes,
            double esEstimatedMinutes,
            double publishEstimatedMinutes
    ) {
        public double totalEstimatedMinutes() {
            return projectionEstimatedMinutes + esEstimatedMinutes + publishEstimatedMinutes;
        }
    }
}
