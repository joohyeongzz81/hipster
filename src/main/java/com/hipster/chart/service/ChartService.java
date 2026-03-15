package com.hipster.chart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.chart.dto.response.TopChartResponse;
import com.hipster.chart.publish.service.ChartPublishedVersionService;
import com.hipster.chart.repository.ChartScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartService {

    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final ChartScoreRepository chartScoreRepository;
    private final ChartSearchService chartSearchService;
    private final ChartResponseAssembler chartResponseAssembler;
    private final ChartLastUpdatedService chartLastUpdatedService;
    private final ChartPublishedVersionService chartPublishedVersionService;
    private final StringRedisTemplate redisTemplate;
    private final ChartCacheKeyGenerator cacheKeyGenerator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public TopChartResponse getCharts(final ChartFilterRequest filter, final int page, final int size) {
        final String cacheKey = cacheKeyGenerator.generateKey(filter, page);

        try {
            final String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null) {
                return objectMapper.readValue(cachedJson, TopChartResponse.class);
            }
        } catch (Exception e) {
            log.warn("[Redis Fallback] 차트 캐시 조회에 실패해 검색 경로로 전환합니다. key={}, reason={}", cacheKey, e.getMessage());
        }

        final Pageable pageable = PageRequest.of(page, size);
        final List<ChartScore> chartScores = fetchChartScores(filter, pageable);

        final LocalDateTime lastUpdated = chartLastUpdatedService.getLastUpdated();

        final TopChartResponse response = chartResponseAssembler.assemble(
                buildChartTitle(size, filter),
                chartPublishedVersionService.getPublishedVersionOrLegacy(),
                lastUpdated,
                chartScores
        );

        try {
            final String jsonResponse = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, jsonResponse, CACHE_TTL);
        } catch (Exception e) {
            log.warn("[Redis Fallback] 차트 캐시 저장에 실패했습니다. key={}, reason={}", cacheKey, e.getMessage());
        }

        return response;
    }

    private List<ChartScore> fetchChartScores(final ChartFilterRequest filter, final Pageable pageable) {
        final ChartFilterRequest safeFilter = filter == null
                ? ChartFilterRequest.empty()
                : filter;

        try {
            final List<Long> releaseIds = chartSearchService.searchReleaseIds(
                    safeFilter,
                    pageable.getPageNumber(),
                    pageable.getPageSize()
            );

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
        } catch (Exception e) {
            log.warn("[Chart Search Fallback] ES 조회에 실패해 MySQL 경로로 전환합니다. reason={}", e.getMessage());
            return chartScoreRepository.findChartsDynamic(safeFilter, pageable);
        }
    }

    private String buildChartTitle(final int limit, final ChartFilterRequest filter) {
        final StringBuilder title = new StringBuilder("Top " + limit + " Releases");
        if (filter != null) {
            if (filter.hasGenreFilter()) {
                title.append(" (Genres ").append(filter.normalizedGenreIds()).append(")");
            }
            if (filter.year() != null) {
                title.append(" (").append(filter.year()).append(")");
            }
            if (filter.releaseType() != null) {
                title.append(" [").append(filter.releaseType()).append("]");
            }
        }
        return title.toString();
    }
}
