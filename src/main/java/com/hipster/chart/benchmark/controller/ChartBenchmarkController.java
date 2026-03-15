package com.hipster.chart.benchmark.controller;

import com.hipster.chart.benchmark.ChartBenchmarkCacheState;
import com.hipster.chart.benchmark.ChartBenchmarkMode;
import com.hipster.chart.benchmark.dto.ChartBenchmarkExplainResponse;
import com.hipster.chart.benchmark.dto.ChartBenchmarkScenarioSpec;
import com.hipster.chart.benchmark.dto.ChartBenchmarkResponse;
import com.hipster.chart.benchmark.service.ChartBenchmarkScenarioResolver;
import com.hipster.chart.benchmark.service.ChartBenchmarkService;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.global.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/benchmarks/charts")
@RequiredArgsConstructor
public class ChartBenchmarkController {

    private final ChartBenchmarkService chartBenchmarkService;
    private final ChartBenchmarkScenarioResolver scenarioResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<ChartBenchmarkResponse>> benchmark(
            @RequestParam final ChartBenchmarkMode mode,
            @RequestParam(required = false) final String scenario,
            @RequestParam(required = false) final ChartBenchmarkCacheState cacheState,
            @ModelAttribute final ChartFilterRequest filter,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {
        final ChartBenchmarkScenarioSpec scenarioSpec = scenarioResolver.resolve(scenario, filter, page, size);
        final ChartBenchmarkResponse response = chartBenchmarkService.benchmark(
                mode,
                scenarioSpec.name(),
                scenarioSpec.filter(),
                scenarioSpec.page(),
                scenarioSpec.size(),
                cacheState
        );
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/explain")
    public ResponseEntity<ApiResponse<ChartBenchmarkExplainResponse>> explain(
            @RequestParam final ChartBenchmarkMode mode,
            @RequestParam(required = false) final String scenario,
            @ModelAttribute final ChartFilterRequest filter,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {
        final ChartBenchmarkScenarioSpec scenarioSpec = scenarioResolver.resolve(scenario, filter, page, size);
        final ChartBenchmarkExplainResponse response = chartBenchmarkService.explain(
                mode,
                scenarioSpec.name(),
                scenarioSpec.filter(),
                scenarioSpec.page(),
                scenarioSpec.size()
        );
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
