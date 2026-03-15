package com.hipster.chart.benchmark.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.chart.benchmark.dto.ChartBenchmarkScenarioFile;
import com.hipster.chart.benchmark.dto.ChartBenchmarkScenarioSpec;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.global.domain.Language;
import com.hipster.release.domain.ReleaseType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChartBenchmarkScenarioResolver {

    private static final String SCENARIO_GLOB = "classpath*:benchmarks/chart/scenarios/*.json";

    private final ObjectMapper objectMapper;
    private final Map<String, ChartBenchmarkScenarioSpec> scenariosByName = new LinkedHashMap<>();

    @PostConstruct
    void loadScenarios() throws IOException {
        final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final Resource[] resources = resolver.getResources(SCENARIO_GLOB);

        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                final ChartBenchmarkScenarioFile file = objectMapper.readValue(inputStream, ChartBenchmarkScenarioFile.class);
                scenariosByName.put(file.name(), toScenarioSpec(file));
            }
        }
    }

    public ChartBenchmarkScenarioSpec resolve(final String scenarioName,
                                              final ChartFilterRequest fallbackFilter,
                                              final int fallbackPage,
                                              final int fallbackSize) {
        if (scenarioName == null || scenarioName.isBlank()) {
            final ChartFilterRequest safeFilter = fallbackFilter == null
                    ? ChartFilterRequest.empty()
                    : fallbackFilter;
            return new ChartBenchmarkScenarioSpec(
                    "ADHOC",
                    "/internal/benchmarks/charts",
                    fallbackPage,
                    fallbackSize,
                    safeFilter,
                    "bayesian_score_desc",
                    false
            );
        }

        final ChartBenchmarkScenarioSpec scenario = scenariosByName.get(scenarioName);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown benchmark scenario: " + scenarioName);
        }
        return scenario;
    }

    private ChartBenchmarkScenarioSpec toScenarioSpec(final ChartBenchmarkScenarioFile file) {
        final ChartBenchmarkScenarioFile.Query query = file.query();
        final ChartFilterRequest filter = new ChartFilterRequest(
                query != null ? query.genreId() : null,
                query != null ? query.genreIds() : null,
                query != null ? query.descriptorId() : null,
                query != null ? query.locationId() : null,
                query != null && query.language() != null ? Language.valueOf(query.language()) : null,
                query != null ? query.year() : null,
                query != null && query.releaseType() != null ? ReleaseType.valueOf(query.releaseType()) : null,
                query != null ? query.includeEsoteric() : Boolean.FALSE
        );

        return new ChartBenchmarkScenarioSpec(
                file.name(),
                file.path() != null ? file.path() : "/api/v1/charts",
                file.page() != null ? file.page() : 0,
                file.size() != null ? file.size() : 20,
                filter,
                file.expect() != null && file.expect().sort() != null ? file.expect().sort() : "bayesian_score_desc",
                file.expect() != null && Boolean.TRUE.equals(file.expect().countIncluded())
        );
    }
}
