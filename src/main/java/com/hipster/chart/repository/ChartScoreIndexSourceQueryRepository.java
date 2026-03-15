package com.hipster.chart.repository;

import com.hipster.chart.config.ChartPublishProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChartScoreIndexSourceQueryRepository {

    private static final String PUBLISHED_TABLE = "chart_scores";
    private static final String BENCHMARK_LIGHT_STAGE_TABLE = "chart_scores_stage_light_bench";

    private final JdbcTemplate jdbcTemplate;
    private final ChartPublishProperties chartPublishProperties;

    public List<ChartScoreIndexRow> findBatchAfterId(final long afterId, final int limit) {
        return findBatchAfterId(ChartScoreIndexSourceType.PUBLISHED, afterId, limit);
    }

    public List<ChartScoreIndexRow> findBatchAfterId(final ChartScoreIndexSourceType sourceType,
                                                     final long afterId,
                                                     final int limit) {
        final String tableName = resolveTableName(sourceType);
        final String cursorColumn = resolveCursorColumn(sourceType);
        final String selectIdExpression = resolveSelectIdExpression(sourceType);
        return jdbcTemplate.query(
                ("""
                SELECT %s AS id,
                       release_id,
                       bayesian_score,
                       is_esoteric,
                       release_type,
                       release_year,
                       location_id,
                       genre_ids,
                       descriptor_ids,
                       languages
                FROM %s
                WHERE %s > ?
                ORDER BY %s ASC
                LIMIT ?
                """).formatted(selectIdExpression, tableName, cursorColumn, cursorColumn),
                (rs, rowNum) -> new ChartScoreIndexRow(
                        rs.getLong("id"),
                        rs.getLong("release_id"),
                        rs.getDouble("bayesian_score"),
                        rs.getBoolean("is_esoteric"),
                        rs.getString("release_type"),
                        getNullableInt(rs, "release_year"),
                        getNullableLong(rs, "location_id"),
                        rs.getString("genre_ids"),
                        rs.getString("descriptor_ids"),
                        rs.getString("languages")
                ),
                afterId,
                limit
        );
    }

    public long resolveStartAfterId(final int startPage, final int batchSize) {
        return resolveStartAfterId(ChartScoreIndexSourceType.PUBLISHED, startPage, batchSize);
    }

    public long resolveStartAfterId(final ChartScoreIndexSourceType sourceType,
                                    final int startPage,
                                    final int batchSize) {
        if (startPage <= 0) {
            return 0L;
        }

        final long offset = ((long) startPage * batchSize) - 1L;
        final String tableName = resolveTableName(sourceType);
        final String cursorColumn = resolveCursorColumn(sourceType);
        final Long startId = jdbcTemplate.query(
                ("SELECT %s FROM %s ORDER BY %s ASC LIMIT 1 OFFSET ?").formatted(cursorColumn, tableName, cursorColumn),
                rs -> rs.next() ? rs.getLong(1) : 0L,
                offset
        );
        return startId == null ? 0L : startId;
    }

    public long countRows(final ChartScoreIndexSourceType sourceType) {
        final Long count = jdbcTemplate.queryForObject(
                ("SELECT COUNT(*) FROM %s").formatted(resolveTableName(sourceType)),
                Long.class
        );
        return count == null ? 0L : count;
    }

    private String resolveTableName(final ChartScoreIndexSourceType sourceType) {
        return switch (sourceType) {
            case PUBLISHED -> PUBLISHED_TABLE;
            case STAGE -> chartPublishProperties.getStageTableName();
            case BENCHMARK_LIGHT_STAGE -> BENCHMARK_LIGHT_STAGE_TABLE;
        };
    }

    private String resolveCursorColumn(final ChartScoreIndexSourceType sourceType) {
        return switch (sourceType) {
            case BENCHMARK_LIGHT_STAGE -> "release_id";
            case PUBLISHED, STAGE -> "id";
        };
    }

    private String resolveSelectIdExpression(final ChartScoreIndexSourceType sourceType) {
        return switch (sourceType) {
            case BENCHMARK_LIGHT_STAGE -> "release_id";
            case PUBLISHED, STAGE -> "id";
        };
    }

    private Integer getNullableInt(final java.sql.ResultSet rs, final String column) throws java.sql.SQLException {
        final int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long getNullableLong(final java.sql.ResultSet rs, final String column) throws java.sql.SQLException {
        final long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ChartScoreIndexRow(
            long id,
            long releaseId,
            double bayesianScore,
            boolean isEsoteric,
            String releaseType,
            Integer releaseYear,
            Long locationId,
            String genreIds,
            String descriptorIds,
            String languages
    ) {
    }

    public enum ChartScoreIndexSourceType {
        PUBLISHED,
        STAGE,
        BENCHMARK_LIGHT_STAGE
    }
}
