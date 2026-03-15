package com.hipster.chart.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.domain.ChartDocument;
import com.hipster.chart.repository.ChartScoreIndexSourceQueryRepository;
import com.hipster.chart.repository.ChartScoreIndexSourceQueryRepository.ChartScoreIndexRow;
import com.hipster.chart.repository.ChartScoreIndexSourceQueryRepository.ChartScoreIndexSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartElasticsearchIndexService {

    private static final int DEFAULT_BATCH_SIZE = 2_000;
    private static final DateTimeFormatter VERSION_SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final TypeReference<List<Map<String, Object>>> GENRE_JSON_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Integer>> INTEGER_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ChartScoreIndexSourceQueryRepository chartScoreIndexSourceQueryRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;
    private final ChartPublishProperties chartPublishProperties;

    @Value("${chart.search.index-name:chart_scores}")
    private String chartSearchIndexName;

    @Transactional(readOnly = true)
    public void rebuildIndex() {
        rebuildIndex(chartSearchIndexName, DEFAULT_BATCH_SIZE, ChartScoreIndexSourceType.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public void rebuildIndex(final String indexName, final int batchSize) {
        rebuildIndex(indexName, batchSize, ChartScoreIndexSourceType.PUBLISHED);
    }

    @Transactional(readOnly = true)
    public void rebuildCandidateIndex(final String version, final int batchSize) {
        rebuildIndex(buildCandidateIndexName(version), batchSize, ChartScoreIndexSourceType.STAGE);
    }

    @Transactional(readOnly = true)
    public ElasticsearchIndexSample benchmarkSample(final int startPage, final int pageCount, final int batchSize) {
        final String sampleIndexName = chartSearchIndexName + "_sample";
        final IndexCoordinates indexCoordinates = IndexCoordinates.of(sampleIndexName);

        recreateIndex(indexCoordinates);

        final long totalChartScores = chartScoreIndexSourceQueryRepository.countRows(ChartScoreIndexSourceType.PUBLISHED);
        final long totalPages = totalChartScores == 0 ? 0 : (long) Math.ceil((double) totalChartScores / batchSize);
        long cursorId = chartScoreIndexSourceQueryRepository.resolveStartAfterId(
                ChartScoreIndexSourceType.PUBLISHED,
                startPage,
                batchSize
        );

        long indexedCount = 0L;
        long totalFetchMillis = 0L;
        long totalIndexMillis = 0L;
        int sampledPages = 0;

        for (int pageNumber = startPage; pageNumber < startPage + pageCount; pageNumber++) {
            final long fetchStart = System.nanoTime();
            final List<ChartScoreIndexRow> rows = chartScoreIndexSourceQueryRepository.findBatchAfterId(
                    ChartScoreIndexSourceType.PUBLISHED,
                    cursorId,
                    batchSize
            );
            final long fetchMillis = Duration.ofNanos(System.nanoTime() - fetchStart).toMillis();

            if (rows.isEmpty()) {
                break;
            }

            final List<ChartDocument> documents = rows.stream()
                    .map(this::toDocument)
                    .toList();

            final long indexStart = System.nanoTime();
            elasticsearchOperations.bulkIndex(toIndexQueries(documents), indexCoordinates);
            final long indexMillis = Duration.ofNanos(System.nanoTime() - indexStart).toMillis();

            indexedCount += documents.size();
            totalFetchMillis += fetchMillis;
            totalIndexMillis += indexMillis;
            sampledPages++;
            cursorId = rows.get(rows.size() - 1).id();

            final double localProgressPercent = pageCount == 0 ? 100.0 : ((double) sampledPages / pageCount) * 100.0;
            final double globalProgressPercent = totalPages == 0
                    ? 100.0
                    : ((double) (pageNumber + 1) / totalPages) * 100.0;
            log.info(
                    "[CHART BATCH][ES SAMPLE] progress local={}/{} ({}%), globalPage={}/{} ({}%), docsIndexed={}, fetch={}ms, index={}ms",
                    sampledPages,
                    pageCount,
                    String.format("%.1f", localProgressPercent),
                    pageNumber + 1,
                    totalPages,
                    String.format("%.1f", globalProgressPercent),
                    indexedCount,
                    fetchMillis,
                    indexMillis
            );
        }

        final long refreshStart = System.nanoTime();
        finalizeIndex(indexCoordinates);
        final long refreshMillis = Duration.ofNanos(System.nanoTime() - refreshStart).toMillis();

        return new ElasticsearchIndexSample(
                sampleIndexName,
                totalChartScores,
                totalPages,
                batchSize,
                startPage,
                sampledPages,
                indexedCount,
                totalFetchMillis,
                totalIndexMillis,
                refreshMillis
        );
    }

    public String buildCandidateIndexName(final String version) {
        return chartSearchIndexName + "_" + normalizeVersion(version);
    }

    public String buildBenchmarkIndexName(final String suffix) {
        return chartSearchIndexName + "_full_bench_" + normalizeVersion(suffix);
    }

    public String buildCandidateIndexName(final LocalDateTime logicalAsOfAt) {
        return buildCandidateIndexName("v" + logicalAsOfAt.format(VERSION_SUFFIX_FORMAT));
    }

    public long countDocumentsByVersion(final String version) {
        return countDocuments(buildCandidateIndexName(version));
    }

    public boolean isIndexSearchableByVersion(final String version) {
        final String indexName = buildCandidateIndexName(version);
        if (!elasticsearchOperations.indexOps(IndexCoordinates.of(indexName)).exists()) {
            return false;
        }
        try {
            countDocuments(indexName);
            return true;
        } catch (Exception e) {
            log.warn("[CHART BATCH] Candidate index is not searchable yet. index={}, reason={}", indexName, e.getMessage());
            return false;
        }
    }

    public void publishCandidateAlias(final String version) {
        publishAlias(buildCandidateIndexName(version));
    }

    public void rollbackAliasToIndex(final String indexName) {
        publishAlias(indexName);
    }

    public String resolvePublishedAliasName() {
        return chartPublishProperties.resolveAliasName(chartSearchIndexName);
    }

    public String resolvePublishedAliasTarget() {
        return resolveAliasTarget(resolvePublishedAliasName());
    }

    public String resolvePublishedIndexName() {
        final String aliasName = resolvePublishedAliasName();
        final String aliasTarget = resolveAliasTarget(aliasName);
        return aliasTarget != null ? aliasName : chartSearchIndexName;
    }

    @Transactional(readOnly = true)
    public ElasticsearchIndexSample benchmarkFullRun(final ChartScoreIndexSourceType sourceType,
                                                     final int batchSize,
                                                     final String targetIndexName) {
        final IndexCoordinates indexCoordinates = IndexCoordinates.of(targetIndexName);

        recreateIndex(indexCoordinates);

        final long totalChartScores = chartScoreIndexSourceQueryRepository.countRows(sourceType);
        final long totalPages = totalChartScores == 0 ? 0 : (long) Math.ceil((double) totalChartScores / batchSize);
        long cursorId = 0L;
        long indexedCount = 0L;
        long totalFetchMillis = 0L;
        long totalIndexMillis = 0L;
        int sampledPages = 0;

        while (true) {
            final long fetchStart = System.nanoTime();
            final List<ChartScoreIndexRow> rows = chartScoreIndexSourceQueryRepository.findBatchAfterId(
                    sourceType,
                    cursorId,
                    batchSize
            );
            final long fetchMillis = Duration.ofNanos(System.nanoTime() - fetchStart).toMillis();

            if (rows.isEmpty()) {
                break;
            }

            final List<ChartDocument> documents = rows.stream()
                    .map(this::toDocument)
                    .toList();

            final long indexStart = System.nanoTime();
            elasticsearchOperations.bulkIndex(toIndexQueries(documents), indexCoordinates);
            final long indexMillis = Duration.ofNanos(System.nanoTime() - indexStart).toMillis();

            indexedCount += documents.size();
            totalFetchMillis += fetchMillis;
            totalIndexMillis += indexMillis;
            sampledPages++;
            cursorId = rows.get(rows.size() - 1).id();

            final double globalProgressPercent = totalPages == 0
                    ? 100.0
                    : ((double) sampledPages / totalPages) * 100.0;
            log.info(
                    "[CHART BATCH][ES FULL RUN] progress page={}/{} ({}%), sourceType={}, index={}, docsIndexed={}, fetch={}ms, index={}ms",
                    sampledPages,
                    totalPages,
                    String.format("%.1f", globalProgressPercent),
                    sourceType,
                    targetIndexName,
                    indexedCount,
                    fetchMillis,
                    indexMillis
            );
        }

        final long refreshStart = System.nanoTime();
        finalizeIndex(indexCoordinates);
        final long refreshMillis = Duration.ofNanos(System.nanoTime() - refreshStart).toMillis();

        return new ElasticsearchIndexSample(
                targetIndexName,
                totalChartScores,
                totalPages,
                batchSize,
                0,
                sampledPages,
                indexedCount,
                totalFetchMillis,
                totalIndexMillis,
                refreshMillis
        );
    }

    private void rebuildIndex(final String indexName,
                              final int batchSize,
                              final ChartScoreIndexSourceType sourceType) {
        final IndexCoordinates indexCoordinates = IndexCoordinates.of(indexName);
        recreateIndex(indexCoordinates);

        long indexedCount = 0L;
        long cursorId = 0L;

        while (true) {
            final List<ChartScoreIndexRow> rows = chartScoreIndexSourceQueryRepository.findBatchAfterId(
                    sourceType,
                    cursorId,
                    batchSize
            );

            if (rows.isEmpty()) {
                break;
            }

            final List<ChartDocument> documents = rows.stream()
                    .map(this::toDocument)
                    .toList();

            elasticsearchOperations.bulkIndex(toIndexQueries(documents), indexCoordinates);
            indexedCount += documents.size();
            cursorId = rows.get(rows.size() - 1).id();

            log.info(
                    "[CHART BATCH] ES indexing in progress. sourceType={}, index={}, indexedCount={}",
                    sourceType,
                    indexName,
                    indexedCount
            );
        }

        finalizeIndex(indexCoordinates);
        log.info("[CHART BATCH] ES indexing completed. sourceType={}, index={}, indexedCount={}", sourceType, indexName, indexedCount);
    }

    private void recreateIndex(final IndexCoordinates indexCoordinates) {
        final IndexOperations indexOperations = elasticsearchOperations.indexOps(indexCoordinates);

        if (indexOperations.exists()) {
            indexOperations.delete();
        }

        final Map<String, Object> settings = new HashMap<>();
        settings.put("number_of_replicas", 0);
        settings.put("refresh_interval", "-1");
        settings.put("max_result_window", 50_000);

        indexOperations.create(settings, indexOperations.createMapping(ChartDocument.class));
    }

    private void finalizeIndex(final IndexCoordinates indexCoordinates) {
        final IndexOperations indexOperations = elasticsearchOperations.indexOps(indexCoordinates);
        indexOperations.refresh();
    }

    private void publishAlias(final String targetIndexName) {
        final String aliasName = chartPublishProperties.resolveAliasName(chartSearchIndexName);
        final String currentTarget = resolveAliasTarget(aliasName);
        if (!elasticsearchOperations.indexOps(IndexCoordinates.of(targetIndexName)).exists()) {
            throw new IllegalStateException("Target ES index does not exist: " + targetIndexName);
        }
        if (targetIndexName.equals(currentTarget)) {
            log.info("[CHART BATCH] ES alias already points to target. alias={}, target={}", aliasName, targetIndexName);
            return;
        }

        try {
            elasticsearchClient.indices().updateAliases(builder -> {
                if (currentTarget != null && !currentTarget.equals(targetIndexName)) {
                    builder.actions(action -> action.remove(remove -> remove.index(currentTarget).alias(aliasName)));
                }
                builder.actions(action -> action.add(add -> add.index(targetIndexName).alias(aliasName)));
                return builder;
            });
            log.info("[CHART BATCH] ES alias published. alias={}, target={}", aliasName, targetIndexName);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to switch chart alias. alias=" + aliasName + ", target=" + targetIndexName, e);
        }
    }

    private String resolveAliasTarget(final String aliasName) {
        try {
            final var response = elasticsearchClient.indices().getAlias(getAlias -> getAlias.name(aliasName));
            if (response.result() == null || response.result().isEmpty()) {
                return null;
            }
            return response.result().keySet().stream()
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[CHART BATCH] Failed to resolve ES alias target. alias={}, reason={}", aliasName, e.getMessage());
            return null;
        }
    }

    private long countDocuments(final String indexName) {
        try {
            return elasticsearchClient.count(request -> request.index(indexName)).count();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to count ES documents. index=" + indexName, e);
        }
    }

    private String normalizeVersion(final String version) {
        return version.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    private ChartDocument toDocument(final ChartScoreIndexRow row) {
        final List<Integer> genreIds = extractGenreIds(row.genreIds());
        final List<Integer> primaryGenreIds = extractPrimaryGenreIds(row.genreIds());

        return ChartDocument.builder()
                .releaseId(row.releaseId())
                .bayesianScore(row.bayesianScore())
                .isEsoteric(row.isEsoteric())
                .releaseType(row.releaseType())
                .releaseYear(row.releaseYear())
                .locationId(row.locationId())
                .genreIds(genreIds)
                .isPrimaryGenreIds(primaryGenreIds)
                .descriptorIds(extractIntegerList(row.descriptorIds()))
                .languages(extractStringList(row.languages()))
                .build();
    }

    private List<IndexQuery> toIndexQueries(final List<ChartDocument> documents) {
        final List<IndexQuery> queries = new ArrayList<>(documents.size());
        for (ChartDocument document : documents) {
            queries.add(new IndexQueryBuilder()
                    .withId(String.valueOf(document.getReleaseId()))
                    .withObject(document)
                    .build());
        }
        return queries;
    }

    private List<Integer> extractGenreIds(final String genreIdsJson) {
        return extractGenreMaps(genreIdsJson).stream()
                .map(entry -> entry.get("id"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .toList();
    }

    private List<Integer> extractPrimaryGenreIds(final String genreIdsJson) {
        return extractGenreMaps(genreIdsJson).stream()
                .filter(entry -> Boolean.TRUE.equals(entry.get("isPrimary")))
                .map(entry -> entry.get("id"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .toList();
    }

    private List<Map<String, Object>> extractGenreMaps(final String genreIdsJson) {
        if (genreIdsJson == null || genreIdsJson.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(genreIdsJson, GENRE_JSON_TYPE);
        } catch (Exception e) {
            log.warn("[CHART BATCH] Failed to parse genre_ids JSON. json={}", genreIdsJson, e);
            return List.of();
        }
    }

    private List<Integer> extractIntegerList(final String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(json, INTEGER_LIST_TYPE);
        } catch (Exception e) {
            log.warn("[CHART BATCH] Failed to parse integer array JSON. json={}", json, e);
            return List.of();
        }
    }

    private List<String> extractStringList(final String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            log.warn("[CHART BATCH] Failed to parse string array JSON. json={}", json, e);
            return List.of();
        }
    }

    public record ElasticsearchIndexSample(
            String sampleIndexName,
            long totalChartScores,
            long totalPages,
            int batchSize,
            int startPage,
            int sampledPages,
            long indexedDocuments,
            long totalFetchMillis,
            long totalIndexMillis,
            long refreshMillis
    ) {
        public long totalMillis() {
            return totalFetchMillis + totalIndexMillis + refreshMillis;
        }

        public double avgFetchMillisPerPage() {
            return sampledPages == 0 ? 0.0 : (double) totalFetchMillis / sampledPages;
        }

        public double avgIndexMillisPerPage() {
            return sampledPages == 0 ? 0.0 : (double) totalIndexMillis / sampledPages;
        }

        public double avgTotalMillisPerPage() {
            return sampledPages == 0 ? 0.0 : (double) totalMillis() / sampledPages;
        }

        public double docsPerSecond() {
            final double seconds = totalIndexMillis / 1000.0;
            return seconds <= 0 ? indexedDocuments : indexedDocuments / seconds;
        }

        public double estimatedFullRunMinutes() {
            if (sampledPages == 0 || totalPages == 0) {
                return 0.0;
            }
            return (avgTotalMillisPerPage() * totalPages) / 1000.0 / 60.0;
        }
    }
}
