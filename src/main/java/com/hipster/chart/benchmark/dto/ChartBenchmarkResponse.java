package com.hipster.chart.benchmark.dto;

import com.hipster.chart.dto.response.TopChartResponse;

import java.util.Map;

public record ChartBenchmarkResponse(
        String mode,
        String scenario,
        String cacheState,
        Map<String, Long> timings,
        TopChartResponse result
) {
}
