package com.hipster.chart.publish.repository;

import com.hipster.chart.publish.domain.ChartPublishState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChartPublishStateRepository extends JpaRepository<ChartPublishState, String> {
}
