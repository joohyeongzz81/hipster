package com.hipster.chart.benchmark.repository;

import com.hipster.chart.dto.request.ChartFilterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.StringJoiner;

@Repository
@RequiredArgsConstructor
public class ChartBenchmarkQueryRepository {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public List<Long> findReleaseIdsByNaiveJoin(final ChartFilterRequest filter, final int page, final int size) {
        final BenchmarkQuerySpec querySpec = buildNaiveJoinQuerySpec(filter, page, size);
        return namedParameterJdbcTemplate.queryForList(querySpec.sql(), querySpec.params(), Long.class);
    }

    public List<Long> findReleaseIdsByJsonNative(final ChartFilterRequest filter,
                                                 final int page,
                                                 final int size) {
        final BenchmarkQuerySpec querySpec = buildJsonNativeQuerySpec(filter, page, size);
        return namedParameterJdbcTemplate.queryForList(querySpec.sql(), querySpec.params(), Long.class);
    }

    public List<Long> findReleaseIdsByIndexedProjection(final ChartFilterRequest filter,
                                                        final int page,
                                                        final int size) {
        final BenchmarkQuerySpec querySpec = buildIndexedProjectionQuerySpec(filter, page, size);
        return namedParameterJdbcTemplate.queryForList(querySpec.sql(), querySpec.params(), Long.class);
    }

    public List<String> explainAnalyzeNaiveJoin(final ChartFilterRequest filter, final int page, final int size) {
        return explainAnalyze(buildNaiveJoinQuerySpec(filter, page, size));
    }

    public List<String> explainAnalyzeJsonNative(final ChartFilterRequest filter, final int page, final int size) {
        return explainAnalyze(buildJsonNativeQuerySpec(filter, page, size));
    }

    public List<String> explainAnalyzeIndexedProjection(final ChartFilterRequest filter, final int page, final int size) {
        return explainAnalyze(buildIndexedProjectionQuerySpec(filter, page, size));
    }

    private List<String> explainAnalyze(final BenchmarkQuerySpec querySpec) {
        return namedParameterJdbcTemplate.query(
                "EXPLAIN ANALYZE " + querySpec.sql(),
                querySpec.params(),
                (rs, rowNum) -> rs.getString(1)
        );
    }

    private BenchmarkQuerySpec buildNaiveJoinQuerySpec(final ChartFilterRequest filter, final int page, final int size) {
        final String sql = """
                SELECT naive.release_id
                FROM (
                    SELECT cs.release_id AS release_id, MAX(cs.bayesian_score) AS max_score
                    FROM chart_scores cs
                    JOIN releases r ON r.id = cs.release_id
                    %s
                    LEFT JOIN release_descriptors rd ON rd.release_id = r.id
                    LEFT JOIN release_languages rl ON rl.release_id = r.id
                    WHERE (:includeEsoteric = true OR cs.is_esoteric = false)
                      %s
                      AND (:descriptorId IS NULL OR rd.descriptor_id = :descriptorId)
                      AND (:locationId IS NULL OR r.location_id = :locationId)
                      AND (:language IS NULL OR rl.language = :language)
                      AND (:releaseType IS NULL OR r.release_type = :releaseType)
                      AND (:year IS NULL OR YEAR(r.release_date) = :year)
                    GROUP BY cs.release_id
                ) naive
                ORDER BY naive.max_score DESC
                LIMIT :limit OFFSET :offset
                """.formatted(buildGenreJoinClause(filter), buildGenreWhereClause(filter));

        return new BenchmarkQuerySpec(sql, buildParams(filter, page, size));
    }

    private BenchmarkQuerySpec buildJsonNativeQuerySpec(final ChartFilterRequest filter, final int page, final int size) {
        final String sql = """
                SELECT c.release_id
                FROM chart_scores c
                %s
                WHERE (:includeEsoteric = true OR c.is_esoteric = false)
                  %s
                  AND (:descriptorId IS NULL OR JSON_CONTAINS(c.descriptor_ids, CAST(:descriptorId AS CHAR)))
                  AND (:locationId IS NULL OR c.location_id = :locationId)
                  AND (:language IS NULL OR JSON_CONTAINS(c.languages, CONCAT('\"', :language, '\"')))
                  AND (:releaseType IS NULL OR c.release_type = :releaseType)
                  AND (:year IS NULL OR c.release_year = :year)
                ORDER BY c.bayesian_score DESC
                LIMIT :limit OFFSET :offset
                """.formatted(resolveChapter2IndexHint(), buildJsonGenreClause(filter));

        return new BenchmarkQuerySpec(sql, buildParams(filter, page, size));
    }

    private BenchmarkQuerySpec buildIndexedProjectionQuerySpec(final ChartFilterRequest filter,
                                                               final int page,
                                                               final int size) {
        final StringBuilder sql = new StringBuilder("""
                SELECT c.release_id
                FROM chart_scores c
                WHERE 1=1
                """);

        if (!Boolean.TRUE.equals(filter.includeEsoteric())) {
            sql.append("\n  AND c.is_esoteric = false");
        }
        int genreIndex = 0;
        for (Long genreId : filter.normalizedGenreIds()) {
            sql.append("\n  AND JSON_CONTAINS(c.genre_ids, JSON_OBJECT('id', :genreId")
                    .append(genreIndex)
                    .append("))");
            genreIndex++;
        }
        if (filter.descriptorId() != null) {
            sql.append("\n  AND JSON_CONTAINS(c.descriptor_ids, CAST(:descriptorId AS CHAR))");
        }
        if (filter.locationId() != null) {
            sql.append("\n  AND c.location_id = :locationId");
        }
        if (filter.language() != null) {
            sql.append("\n  AND JSON_CONTAINS(c.languages, CONCAT('\"', :language, '\"'))");
        }
        if (filter.releaseType() != null) {
            sql.append("\n  AND c.release_type = :releaseType");
        }
        if (filter.year() != null) {
            sql.append("\n  AND c.release_year = :year");
        }

        sql.append("""

                ORDER BY c.bayesian_score DESC
                LIMIT :limit OFFSET :offset
                """);

        return new BenchmarkQuerySpec(sql.toString(), buildParams(filter, page, size));
    }

    private MapSqlParameterSource buildParams(final ChartFilterRequest filter, final int page, final int size) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("includeEsoteric", Boolean.TRUE.equals(filter.includeEsoteric()))
                .addValue("genreId", filter.genreId())
                .addValue("descriptorId", filter.descriptorId())
                .addValue("locationId", filter.locationId())
                .addValue("language", filter.language() != null ? filter.language().name() : null)
                .addValue("releaseType", filter.releaseType() != null ? filter.releaseType().name() : null)
                .addValue("year", filter.year())
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        int index = 0;
        for (Long genreId : filter.normalizedGenreIds()) {
            params.addValue("genreId" + index, genreId);
            index++;
        }
        return params;
    }

    private String buildGenreJoinClause(final ChartFilterRequest filter) {
        final List<Long> genreIds = filter.normalizedGenreIds();
        if (genreIds.isEmpty()) {
            return "LEFT JOIN release_genres rg ON rg.release_id = r.id";
        }

        final StringJoiner joiner = new StringJoiner("\n                    ");
        for (int index = 0; index < genreIds.size(); index++) {
            joiner.add("JOIN release_genres rg" + index + " ON rg" + index + ".release_id = r.id");
        }
        return joiner.toString();
    }

    private String buildGenreWhereClause(final ChartFilterRequest filter) {
        final List<Long> genreIds = filter.normalizedGenreIds();
        if (genreIds.isEmpty()) {
            return "";
        }

        final StringJoiner joiner = new StringJoiner("\n                      ");
        for (int index = 0; index < genreIds.size(); index++) {
            joiner.add("AND rg" + index + ".genre_id = :genreId" + index);
        }
        return joiner.toString();
    }

    private String buildJsonGenreClause(final ChartFilterRequest filter) {
        final List<Long> genreIds = filter.normalizedGenreIds();
        if (genreIds.isEmpty()) {
            return "";
        }

        final StringJoiner joiner = new StringJoiner("\n                  ");
        for (int index = 0; index < genreIds.size(); index++) {
            joiner.add("AND JSON_CONTAINS(c.genre_ids, JSON_OBJECT('id', :genreId" + index + "))");
        }
        return joiner.toString();
    }

    private String resolveChapter2IndexHint() {
        final String sql = """
                SELECT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'chart_scores'
                  AND index_name IN ('idx_chart_scores_filter_sort', 'idx_chart_scores_covering')
                GROUP BY index_name
                ORDER BY index_name
                """;

        final List<String> existingIndexes = namedParameterJdbcTemplate.queryForList(sql, new MapSqlParameterSource(), String.class);
        if (existingIndexes.isEmpty()) {
            return "";
        }

        final StringJoiner joiner = new StringJoiner(", ", "IGNORE INDEX (", ")");
        existingIndexes.forEach(joiner::add);
        return joiner.toString();
    }

    private record BenchmarkQuerySpec(String sql, MapSqlParameterSource params) {
    }
}
