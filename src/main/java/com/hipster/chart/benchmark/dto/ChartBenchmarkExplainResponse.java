package com.hipster.chart.benchmark.dto;

import java.util.List;

public record ChartBenchmarkExplainResponse(
        String mode,
        String scenario,
        List<String> lines
) {
}
