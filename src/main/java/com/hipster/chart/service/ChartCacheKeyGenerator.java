package com.hipster.chart.service;

import com.hipster.chart.publish.service.ChartPublishedVersionService;
import com.hipster.chart.dto.request.ChartFilterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ChartCacheKeyGenerator {

    private static final String CACHE_PREFIX = "chart:v1:";
    private final ChartPublishedVersionService chartPublishedVersionService;

    public String generateKey(final ChartFilterRequest filter, final int page) {
        final String prefix = resolveCachePrefix();
        if (filter == null || isFilterEmpty(filter)) {
            return prefix + "all:page:" + page;
        }

        // 파라미터를 알파벳 순서(TreeMap)로 정렬하여 직렬화
        final Map<String, String> params = new TreeMap<>();
        if (filter.hasGenreFilter()) {
            params.put("genreIds", filter.normalizedGenreIds().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }
        if (filter.descriptorId() != null) params.put("descriptorId", filter.descriptorId().toString());
        if (filter.locationId() != null) params.put("locationId", filter.locationId().toString());
        if (filter.language() != null) params.put("language", filter.language().name());
        if (filter.year() != null) params.put("year", filter.year().toString());
        if (filter.releaseType() != null) params.put("releaseType", filter.releaseType().name());
        if (Boolean.TRUE.equals(filter.includeEsoteric())) params.put("includeEsoteric", "true");

        final String serializedParams = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        return prefix + serializedParams + ":page:" + page;
    }

    private String resolveCachePrefix() {
        return CACHE_PREFIX + chartPublishedVersionService.getPublishedVersion() + ":";
    }

    private boolean isFilterEmpty(final ChartFilterRequest filter) {
        return !filter.hasGenreFilter() &&
               filter.descriptorId() == null &&
               filter.locationId() == null &&
               filter.language() == null &&
               filter.year() == null &&
               filter.releaseType() == null &&
               !Boolean.TRUE.equals(filter.includeEsoteric());
    }
}
