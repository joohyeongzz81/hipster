package com.hipster.batch.chart.repository;

import com.hipster.batch.chart.benchmark.ChartProjectionWriteMode;
import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.chart.config.ChartPublishProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChartScoreQueryRepository {

    private static final String PUBLISHED_TABLE = "chart_scores";
    private static final String FAILED_PUBLISHED_TABLE = "chart_scores_failed";
    private static final String BENCHMARK_STAGE_TABLE = "chart_scores_stage_bench";
    private static final String BENCHMARK_LIGHT_STAGE_TABLE = "chart_scores_stage_light_bench";

    private final JdbcTemplate jdbcTemplate;
    private final ChartPublishProperties chartPublishProperties;

    /**
     * ChartScore 청크를 단일 배치 SQL로 UPSERT.
     * releaseId UNIQUE 제약 조건을 이용하여 신규/기존 모두 처리.
     */
    public void bulkUpsertChartScores(final List<ChartScoreDto> scores) {
        bulkUpsert(scores, PUBLISHED_TABLE);
    }

    public void bulkUpsertPublishStageChartScores(final List<ChartScoreDto> scores) {
        bulkUpsert(scores, chartPublishProperties.getStageTableName());
    }

    private void bulkUpsert(final List<ChartScoreDto> scores, final String tableName) {
        if (scores == null || scores.isEmpty()) return;

        final String sql = ("""
                INSERT INTO %s (
                    release_id, bayesian_score, weighted_avg_rating,
                    effective_votes, total_ratings, is_esoteric,
                    genre_ids, release_type, release_year, descriptor_ids, location_id, languages,
                    last_updated, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())
                ON DUPLICATE KEY UPDATE
                    bayesian_score      = VALUES(bayesian_score),
                    weighted_avg_rating = VALUES(weighted_avg_rating),
                    effective_votes     = VALUES(effective_votes),
                    total_ratings       = VALUES(total_ratings),
                    is_esoteric         = VALUES(is_esoteric),
                    genre_ids           = VALUES(genre_ids),
                    release_type        = VALUES(release_type),
                    release_year        = VALUES(release_year),
                    descriptor_ids      = VALUES(descriptor_ids),
                    location_id         = VALUES(location_id),
                    languages           = VALUES(languages),
                    last_updated        = NOW(),
                    updated_at          = NOW()
                """).formatted(tableName);

        jdbcTemplate.batchUpdate(sql, scores, scores.size(), (ps, dto) -> {
            bindChartScore(ps, dto);
        });
    }

    public void bulkInsertBenchmarkStageChartScores(final List<ChartScoreDto> scores) {
        if (scores == null || scores.isEmpty()) return;

        final String sql = """
                INSERT INTO chart_scores_stage_bench (
                    release_id, bayesian_score, weighted_avg_rating,
                    effective_votes, total_ratings, is_esoteric,
                    genre_ids, release_type, release_year, descriptor_ids, location_id, languages,
                    last_updated, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())
                """;

        jdbcTemplate.batchUpdate(sql, scores, scores.size(), (ps, dto) -> {
            bindChartScore(ps, dto);
        });
    }

    public void bulkInsertBenchmarkLightStageChartScores(final List<ChartScoreDto> scores) {
        if (scores == null || scores.isEmpty()) return;

        final String sql = """
                INSERT INTO chart_scores_stage_light_bench (
                    release_id, bayesian_score, weighted_avg_rating,
                    effective_votes, total_ratings, is_esoteric,
                    genre_ids, release_type, release_year, descriptor_ids, location_id, languages,
                    last_updated, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW())
                """;

        jdbcTemplate.batchUpdate(sql, scores, scores.size(), (ps, dto) -> {
            bindChartScore(ps, dto);
        });
    }

    public void prepareBenchmarkStageTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + BENCHMARK_STAGE_TABLE + " LIKE chart_scores");
        jdbcTemplate.execute("TRUNCATE TABLE " + BENCHMARK_STAGE_TABLE);
    }

    public void preparePublishStageTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + chartPublishProperties.getStageTableName() + " LIKE " + PUBLISHED_TABLE);
        jdbcTemplate.execute("TRUNCATE TABLE " + chartPublishProperties.getStageTableName());
    }

    public long countPublishStageRows() {
        final Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + chartPublishProperties.getStageTableName(),
                Long.class
        );
        return count == null ? 0L : count;
    }

    public void publishStageTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getPreviousTableName());
        jdbcTemplate.execute(
                "RENAME TABLE " + PUBLISHED_TABLE + " TO " + chartPublishProperties.getPreviousTableName()
                        + ", " + chartPublishProperties.getStageTableName() + " TO " + PUBLISHED_TABLE
        );
        jdbcTemplate.execute("CREATE TABLE " + chartPublishProperties.getStageTableName() + " LIKE " + PUBLISHED_TABLE);
    }

    public void rollbackPublishedTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + FAILED_PUBLISHED_TABLE);
        jdbcTemplate.execute(
                "RENAME TABLE " + PUBLISHED_TABLE + " TO " + FAILED_PUBLISHED_TABLE
                        + ", " + chartPublishProperties.getPreviousTableName() + " TO " + PUBLISHED_TABLE
        );
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + chartPublishProperties.getStageTableName() + " LIKE " + PUBLISHED_TABLE);
        jdbcTemplate.execute("TRUNCATE TABLE " + chartPublishProperties.getStageTableName());
    }

    public void prepareBenchmarkLightStageTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + BENCHMARK_LIGHT_STAGE_TABLE);
        jdbcTemplate.execute("""
                CREATE TABLE chart_scores_stage_light_bench (
                    release_id BIGINT NOT NULL,
                    bayesian_score DOUBLE NOT NULL,
                    weighted_avg_rating DOUBLE NOT NULL,
                    effective_votes DOUBLE NOT NULL,
                    total_ratings BIGINT NOT NULL,
                    is_esoteric BIT(1) NOT NULL,
                    descriptor_id BIGINT NULL,
                    genre_id BIGINT NULL,
                    language VARCHAR(16) NULL,
                    location_id BIGINT NULL,
                    release_type VARCHAR(32) NULL,
                    release_year INT NULL,
                    descriptor_ids JSON NULL,
                    genre_ids JSON NULL,
                    languages JSON NULL,
                    last_updated DATETIME(6) NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (release_id)
                )
                """);
    }

    private void bindChartScore(final java.sql.PreparedStatement ps, final ChartScoreDto dto) throws java.sql.SQLException {
        ps.setLong(1, dto.releaseId());
        ps.setDouble(2, dto.bayesianScore());
        ps.setDouble(3, dto.weightedAvgRating());
        ps.setDouble(4, dto.effectiveVotes());
        ps.setLong(5, dto.totalRatings());
        ps.setBoolean(6, dto.isEsoteric());

        if (dto.genreIds() != null) ps.setString(7, dto.genreIds()); else ps.setNull(7, java.sql.Types.VARCHAR);
        if (dto.releaseType() != null) ps.setString(8, dto.releaseType().name()); else ps.setNull(8, java.sql.Types.VARCHAR);
        if (dto.releaseYear() != null) ps.setInt(9, dto.releaseYear()); else ps.setNull(9, java.sql.Types.INTEGER);
        if (dto.descriptorIds() != null) ps.setString(10, dto.descriptorIds()); else ps.setNull(10, java.sql.Types.VARCHAR);
        if (dto.locationId() != null) ps.setLong(11, dto.locationId()); else ps.setNull(11, java.sql.Types.BIGINT);
        if (dto.languages() != null) ps.setString(12, dto.languages()); else ps.setNull(12, java.sql.Types.VARCHAR);
    }
}
