package com.hipster.chart.benchmark.dto;

import com.hipster.chart.dto.request.ChartFilterRequest;

public record ChartBenchmarkScenarioSpec(
        String name,
        String path,
        int page,
        int size,
        ChartFilterRequest filter,
        String sort,
        boolean countIncluded
) {
}
