package com.hipster.batch.chart.repository;

import com.hipster.batch.chart.dto.ChartScoreDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChartScoreQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * ChartScore 청크를 단일 배치 SQL로 UPSERT.
     * releaseId UNIQUE 제약 조건을 이용하여 신규/기존 모두 처리.
     */
    public void bulkUpsertChartScores(final List<ChartScoreDto> scores) {
        if (scores == null || scores.isEmpty()) return;

        final String sql = """
                INSERT INTO chart_scores (release_id, bayesian_score, weighted_avg_rating,
                    effective_votes, total_ratings, is_esoteric, last_updated, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())
                ON DUPLICATE KEY UPDATE
                    bayesian_score      = VALUES(bayesian_score),
                    weighted_avg_rating = VALUES(weighted_avg_rating),
                    effective_votes     = VALUES(effective_votes),
                    total_ratings       = VALUES(total_ratings),
                    is_esoteric         = VALUES(is_esoteric),
                    last_updated        = NOW(),
                    updated_at          = NOW()
                """;

        jdbcTemplate.batchUpdate(sql, scores, scores.size(), (ps, dto) -> {
            ps.setLong(1, dto.releaseId());
            ps.setDouble(2, dto.bayesianScore());
            ps.setDouble(3, dto.weightedAvgRating());
            ps.setDouble(4, dto.effectiveVotes());
            ps.setLong(5, dto.totalRatings());
            ps.setBoolean(6, dto.isEsoteric());
        });
    }
}

