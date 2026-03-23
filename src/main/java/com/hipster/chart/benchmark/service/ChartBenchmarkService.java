package com.hipster.chart.benchmark.service;

import com.hipster.chart.benchmark.ChartBenchmarkCacheState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.chart.benchmark.ChartBenchmarkMode;
import com.hipster.chart.benchmark.dto.ChartBenchmarkExplainResponse;
import com.hipster.chart.benchmark.dto.ChartBenchmarkResponse;
import com.hipster.chart.benchmark.repository.ChartBenchmarkQueryRepository;
import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.chart.dto.response.TopChartResponse;
import com.hipster.chart.publish.service.ChartPublishedVersionService;
import com.hipster.chart.repository.ChartScoreRepository;
import com.hipster.chart.service.ChartCacheKeyGenerator;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.chart.service.ChartResponseAssembler;
import com.hipster.chart.service.ChartSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChartBenchmarkService {

    private final ChartBenchmarkQueryRepository benchmarkQueryRepository;
    private final ChartScoreRepository chartScoreRepository;
    private final ChartSearchService chartSearchService;
    private final ChartResponseAssembler chartResponseAssembler;
    private final ChartLastUpdatedService chartLastUpdatedService;
    private final ChartPublishedVersionService chartPublishedVersionService;
    private final StringRedisTemplate redisTemplate;
    private final ChartCacheKeyGenerator cacheKeyGenerator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ChartBenchmarkResponse benchmark(final ChartBenchmarkMode mode,
                                            final String scenarioName,
                                            final ChartFilterRequest filter,
                                            final int page,
                                            final int size,
                                            final ChartBenchmarkCacheState cacheState) {
        final ChartFilterRequest safeFilter = filter == null
                ? ChartFilterRequest.empty()
                : filter;

        if (mode == ChartBenchmarkMode.CH4_REDIS_CACHE) {
            return benchmarkRedisCache(mode, scenarioName, safeFilter, page, size, cacheState);
        }

        final Map<String, Long> timings = new LinkedHashMap<>();
        final long totalStart = System.nanoTime();

        final List<ChartScore> chartScores = switch (mode) {
            case CH1_JOIN_BASELINE -> fetchByNaiveJoin(safeFilter, page, size, timings);
            case CH2_DENORM_READ_MODEL -> fetchByJsonNative(safeFilter, page, size, timings);
            case CH3_QUERY_REWRITE_INDEXED -> fetchByIndexedProjection(safeFilter, page, size, timings);
            case CH4_REDIS_CACHE -> List.of();
            case CH5_ELASTICSEARCH_READ -> fetchByElasticsearch(safeFilter, page, size, timings);
        };

        final long lastUpdatedStart = System.nanoTime();
        final LocalDateTime lastUpdated = chartLastUpdatedService.getLastUpdated();
        timings.put("lastUpdatedMillis", elapsedMillis(lastUpdatedStart));

        final long assembleStart = System.nanoTime();
        final TopChartResponse response = chartResponseAssembler.assemble(
                buildChartTitle(mode, size, safeFilter),
                chartPublishedVersionService.getPublishedVersion(),
                lastUpdated,
                chartScores
        );
        timings.put("assembleMillis", elapsedMillis(assembleStart));
        timings.put("totalMillis", elapsedMillis(totalStart));

        return new ChartBenchmarkResponse(mode.name(), scenarioName, null, timings, response);
    }

    @Transactional(readOnly = true)
    public ChartBenchmarkExplainResponse explain(final ChartBenchmarkMode mode,
                                                 final String scenarioName,
                                                 final ChartFilterRequest filter,
                                                 final int page,
                                                 final int size) {
        final ChartFilterRequest safeFilter = filter == null
                ? ChartFilterRequest.empty()
                : filter;

        final List<String> lines = switch (mode) {
            case CH1_JOIN_BASELINE -> benchmarkQueryRepository.explainAnalyzeNaiveJoin(safeFilter, page, size);
            case CH2_DENORM_READ_MODEL -> benchmarkQueryRepository.explainAnalyzeJsonNative(safeFilter, page, size);
            case CH3_QUERY_REWRITE_INDEXED, CH4_REDIS_CACHE -> benchmarkQueryRepository.explainAnalyzeIndexedProjection(safeFilter, page, size);
            case CH5_ELASTICSEARCH_READ -> List.of("EXPLAIN ANALYZE is not available for Elasticsearch read mode.");
        };

        return new ChartBenchmarkExplainResponse(mode.name(), scenarioName, lines);
    }

    private List<ChartScore> fetchByNaiveJoin(final ChartFilterRequest filter,
                                              final int page,
                                              final int size,
                                              final Map<String, Long> timings) {
        final long searchStart = System.nanoTime();
        final List<Long> releaseIds = benchmarkQueryRepository.findReleaseIdsByNaiveJoin(filter, page, size);
        timings.put("searchMillis", elapsedMillis(searchStart));
        return hydrateChartScores(releaseIds, timings);
    }

    private List<ChartScore> fetchByJsonNative(final ChartFilterRequest filter,
                                               final int page,
                                               final int size,
                                               final Map<String, Long> timings) {
        final long searchStart = System.nanoTime();
        final List<Long> releaseIds = benchmarkQueryRepository.findReleaseIdsByJsonNative(filter, page, size);
        timings.put("searchMillis", elapsedMillis(searchStart));
        return hydrateChartScores(releaseIds, timings);
    }

    private List<ChartScore> fetchByIndexedProjection(final ChartFilterRequest filter,
                                                      final int page,
                                                      final int size,
                                                      final Map<String, Long> timings) {
        final long searchStart = System.nanoTime();
        final List<Long> releaseIds = benchmarkQueryRepository.findReleaseIdsByIndexedProjection(filter, page, size);
        timings.put("searchMillis", elapsedMillis(searchStart));
        return hydrateChartScores(releaseIds, timings);
    }

    private List<ChartScore> fetchByElasticsearch(final ChartFilterRequest filter,
                                                  final int page,
                                                  final int size,
                                                  final Map<String, Long> timings) {
        final long searchStart = System.nanoTime();
        final List<Long> releaseIds = chartSearchService.searchReleaseIds(filter, page, size);
        timings.put("searchMillis", elapsedMillis(searchStart));
        return hydrateChartScores(releaseIds, timings);
    }

    private ChartBenchmarkResponse benchmarkRedisCache(final ChartBenchmarkMode mode,
                                                       final String scenarioName,
                                                       final ChartFilterRequest filter,
                                                       final int page,
                                                       final int size,
                                                       final ChartBenchmarkCacheState cacheState) {
        final String cacheKey = cacheKeyGenerator.generateKey(filter, page);
        final ChartBenchmarkCacheState effectiveCacheState = cacheState == null
                ? ChartBenchmarkCacheState.MISS
                : cacheState;
        final Map<String, Long> timings = new LinkedHashMap<>();

        if (effectiveCacheState == ChartBenchmarkCacheState.COLD_MISS
                || effectiveCacheState == ChartBenchmarkCacheState.MISS) {
            final long evictStart = System.nanoTime();
            if (effectiveCacheState == ChartBenchmarkCacheState.COLD_MISS) {
                redisTemplate.delete(redisTemplate.keys("chart:*"));
            } else {
                redisTemplate.delete(cacheKey);
            }
            timings.put("cacheEvictMillis", elapsedMillis(evictStart));
            final long measuredStart = System.nanoTime();
            final TopChartResponse response = buildMysqlBackedResponse(mode, filter, page, size, timings);
            writeCache(cacheKey, response, timings, "cacheWriteMillis");
            timings.put("totalMillis", elapsedMillis(measuredStart));
            return new ChartBenchmarkResponse(
                    mode.name(),
                    scenarioName,
                    effectiveCacheState.name(),
                    timings,
                    response
            );
        }

        final long measuredStart = System.nanoTime();
        final long cacheReadStart = System.nanoTime();
        final String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        timings.put("cacheReadMillis", elapsedMillis(cacheReadStart));

        if (cachedJson != null) {
            try {
                final long deserializeStart = System.nanoTime();
                final TopChartResponse cached = objectMapper.readValue(cachedJson, TopChartResponse.class);
                timings.put("cacheDeserializeMillis", elapsedMillis(deserializeStart));
                timings.put("cacheHit", 1L);
                timings.put("totalMillis", elapsedMillis(measuredStart));
                return new ChartBenchmarkResponse(
                        mode.name(),
                        scenarioName,
                        effectiveCacheState.name(),
                        timings,
                        cached
                );
            } catch (Exception e) {
                timings.put("cacheDeserializeMillis", -1L);
            }
        }

        timings.put("cacheHit", 0L);
        final TopChartResponse response = buildMysqlBackedResponse(mode, filter, page, size, timings);
        writeCache(cacheKey, response, timings, "cacheWriteMillis");
        timings.put("totalMillis", elapsedMillis(measuredStart));
        return new ChartBenchmarkResponse(
                mode.name(),
                scenarioName,
                effectiveCacheState.name(),
                timings,
                response
        );
    }

    private TopChartResponse buildMysqlBackedResponse(final ChartBenchmarkMode mode,
                                                      final ChartFilterRequest filter,
                                                      final int page,
                                                      final int size,
                                                      final Map<String, Long> timings) {
        final List<ChartScore> chartScores = fetchByIndexedProjection(filter, page, size, timings);

        final long lastUpdatedStart = System.nanoTime();
        final LocalDateTime lastUpdated = chartLastUpdatedService.getLastUpdated();
        timings.put("lastUpdatedMillis", elapsedMillis(lastUpdatedStart));

        final long assembleStart = System.nanoTime();
        final TopChartResponse response = chartResponseAssembler.assemble(
                buildChartTitle(mode, size, filter),
                chartPublishedVersionService.getPublishedVersion(),
                lastUpdated,
                chartScores
        );
        timings.put("assembleMillis", elapsedMillis(assembleStart));
        return response;
    }

    private void writeCache(final String cacheKey,
                            final TopChartResponse response,
                            final Map<String, Long> timings,
                            final String metricName) {
        try {
            final long cacheWriteStart = System.nanoTime();
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response));
            timings.put(metricName, elapsedMillis(cacheWriteStart));
        } catch (Exception e) {
            timings.put(metricName, -1L);
        }
    }

    private List<ChartScore> hydrateChartScores(final List<Long> releaseIds, final Map<String, Long> timings) {
        final long hydrateStart = System.nanoTime();
        final List<ChartScore> chartScores = findOrderedChartScores(releaseIds);
        timings.put("hydrateMillis", elapsedMillis(hydrateStart));
        return chartScores;
    }

    private List<ChartScore> findOrderedChartScores(final List<Long> releaseIds) {
        if (releaseIds.isEmpty()) {
            return List.of();
        }

        final Map<Long, ChartScore> chartScoreByReleaseId = chartScoreRepository.findAllWithReleaseByReleaseIds(releaseIds)
                .stream()
                .collect(Collectors.toMap(ChartScore::getReleaseId, Function.identity()));

        final List<ChartScore> orderedChartScores = new ArrayList<>();
        for (Long releaseId : releaseIds) {
            final ChartScore chartScore = chartScoreByReleaseId.get(releaseId);
            if (chartScore != null) {
                orderedChartScores.add(chartScore);
            }
        }
        return orderedChartScores;
    }

    private String buildChartTitle(final ChartBenchmarkMode mode, final int size, final ChartFilterRequest filter) {
        final StringBuilder title = new StringBuilder(mode.name()).append(" Top ").append(size).append(" Releases");
        if (filter.hasGenreFilter()) {
            title.append(" (Genres ").append(filter.normalizedGenreIds()).append(")");
        }
        if (filter.year() != null) {
            title.append(" (").append(filter.year()).append(")");
        }
        if (filter.releaseType() != null) {
            title.append(" [").append(filter.releaseType()).append("]");
        }
        return title.toString();
    }

    private long elapsedMillis(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
