package com.hipster.batch.chart.step;

import com.hipster.rating.domain.ReleaseRatingSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;

@Configuration
@RequiredArgsConstructor
public class ChartItemReaderConfig {
    private static final int FETCH_SIZE = 2000;

    private final DataSource dataSource;

    @Bean
    public org.springframework.batch.item.database.JdbcPagingItemReader<ReleaseRatingSummary> chartItemReader(
            org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean queryProvider
    ) throws Exception {
        return new org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder<ReleaseRatingSummary>()
                .fetchSize(FETCH_SIZE)
                .pageSize(FETCH_SIZE)
                .dataSource(dataSource)
                .rowMapper(releaseRatingSummaryRowMapper())
                .queryProvider(queryProvider.getObject())
                .name("chartItemReader")
                .build();
    }

    @Bean
    public org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean chartQueryProvider() {
        org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean factory = new org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean();
        factory.setDataSource(dataSource);
        factory.setSelectClause("SELECT id, release_id, total_rating_count, average_score, weighted_score_sum, weighted_count_sum, batch_synced_at, updated_at");
        factory.setFromClause("FROM release_rating_summary");
        factory.setSortKey("id");
        return factory;
    }

    private RowMapper<ReleaseRatingSummary> releaseRatingSummaryRowMapper() {
        return (rs, rowNum) -> {
            ReleaseRatingSummary summary = new ReleaseRatingSummary(
                    rs.getLong("release_id")
            );
            summary.recalculate(
                    rs.getLong("total_rating_count"),
                    rs.getDouble("average_score"),
                    rs.getBigDecimal("weighted_score_sum"),
                    rs.getBigDecimal("weighted_count_sum")
            );
            return summary;
        };
    }
}
