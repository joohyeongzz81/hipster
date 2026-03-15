package com.hipster.chart.repository;

import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.dto.request.ChartFilterRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ChartScoreRepositoryCustom {
    List<ChartScore> findChartsDynamic(ChartFilterRequest filter, Pageable pageable);
}
