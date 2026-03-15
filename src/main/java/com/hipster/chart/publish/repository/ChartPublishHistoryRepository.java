package com.hipster.chart.publish.repository;

import com.hipster.chart.publish.domain.ChartPublishHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChartPublishHistoryRepository extends JpaRepository<ChartPublishHistory, Long> {

    Optional<ChartPublishHistory> findTopByChartNameAndVersionOrderByCreatedAtDesc(String chartName, String version);
}
