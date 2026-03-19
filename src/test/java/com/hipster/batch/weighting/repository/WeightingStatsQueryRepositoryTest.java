package com.hipster.batch.weighting.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WeightingStatsQueryRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("리뷰 bonus 집계 SQL 은 ACTIVE 이고 공개된 리뷰만 읽는다")
    void findStatsByUserIds_UsesActivePublishedReviewFilter() {
        WeightingStatsQueryRepository repository = new WeightingStatsQueryRepository(namedParameterJdbcTemplate, jdbcTemplate);

        repository.findStatsByUserIds(List.of(1L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM reviews");
        assertThat(sql).contains("status = 'ACTIVE'");
        assertThat(sql).contains("is_published = TRUE");
    }
}
