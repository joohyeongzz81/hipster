package com.hipster.chart.benchmark.dto;

import java.util.List;

public record ChartBenchmarkScenarioFile(
        String name,
        String path,
        Integer page,
        Integer size,
        Query query,
        Expect expect
) {
    public record Query(
            Long genreId,
            List<Long> genreIds,
            Long descriptorId,
            Long locationId,
            String language,
            Integer year,
            String releaseType,
            Boolean includeEsoteric
    ) {
    }

    public record Expect(
            String sort,
            Boolean countIncluded
    ) {
    }
}
