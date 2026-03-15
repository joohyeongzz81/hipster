package com.hipster.chart.repository;

import com.hipster.chart.domain.ChartDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ChartElasticsearchRepository extends ElasticsearchRepository<ChartDocument, Long> {
}