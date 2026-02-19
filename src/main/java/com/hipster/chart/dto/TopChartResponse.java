package com.hipster.chart.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record TopChartResponse(
        String chartType,
        LocalDateTime lastUpdated,
        List<ChartEntryResponse> entries
) {
}
