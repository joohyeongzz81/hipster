package com.hipster.batch.chart.step;

import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class ChartItemReaderConfig {

    private static final int PAGE_SIZE = 500;

    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;

    @Bean
    public RepositoryItemReader<ReleaseRatingSummary> chartItemReader() {
        return new RepositoryItemReaderBuilder<ReleaseRatingSummary>()
                .name("chartItemReader")
                .repository(releaseRatingSummaryRepository)
                .methodName("findAll")
                .pageSize(PAGE_SIZE)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }
}

