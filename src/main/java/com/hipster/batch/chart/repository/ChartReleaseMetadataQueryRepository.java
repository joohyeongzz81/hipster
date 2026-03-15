package com.hipster.batch.chart.repository;

import com.hipster.release.domain.ReleaseType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ChartReleaseMetadataQueryRepository {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public Map<Long, ChartReleaseMetadata> findMetadataByReleaseIds(final Collection<Long> releaseIds) {
        if (releaseIds == null || releaseIds.isEmpty()) {
            return Map.of();
        }

        final String sql = """
                SELECT
                    r.id AS release_id,
                    r.release_type AS release_type,
                    YEAR(r.release_date) AS release_year,
                    r.location_id AS location_id,
                    genre_meta.genre_ids AS genre_ids,
                    descriptor_meta.descriptor_ids AS descriptor_ids,
                    language_meta.languages AS languages
                FROM releases r
                LEFT JOIN (
                    SELECT
                        rg.release_id,
                        JSON_ARRAYAGG(
                            JSON_OBJECT(
                                'id', rg.genre_id,
                                'isPrimary', rg.is_primary
                            )
                        ) AS genre_ids
                    FROM release_genres rg
                    WHERE rg.release_id IN (:releaseIds)
                    GROUP BY rg.release_id
                ) genre_meta ON genre_meta.release_id = r.id
                LEFT JOIN (
                    SELECT
                        rd.release_id,
                        JSON_ARRAYAGG(rd.descriptor_id) AS descriptor_ids
                    FROM release_descriptors rd
                    WHERE rd.release_id IN (:releaseIds)
                    GROUP BY rd.release_id
                ) descriptor_meta ON descriptor_meta.release_id = r.id
                LEFT JOIN (
                    SELECT
                        rl.release_id,
                        JSON_ARRAYAGG(rl.language) AS languages
                    FROM release_languages rl
                    WHERE rl.release_id IN (:releaseIds)
                    GROUP BY rl.release_id
                ) language_meta ON language_meta.release_id = r.id
                WHERE r.id IN (:releaseIds)
                """;

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("releaseIds", releaseIds);

        final Map<Long, ChartReleaseMetadata> result = new HashMap<>();
        namedParameterJdbcTemplate.query(sql, params, rs -> {
            final long releaseId = rs.getLong("release_id");
            final String releaseTypeValue = rs.getString("release_type");

            result.put(releaseId, new ChartReleaseMetadata(
                    releaseId,
                    releaseTypeValue != null ? ReleaseType.valueOf(releaseTypeValue) : null,
                    toInteger(rs.getObject("release_year")),
                    toLong(rs.getObject("location_id")),
                    rs.getString("genre_ids"),
                    rs.getString("descriptor_ids"),
                    rs.getString("languages")
            ));
        });
        return result;
    }

    private Integer toInteger(final Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }

    private Long toLong(final Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    public record ChartReleaseMetadata(
            Long releaseId,
            ReleaseType releaseType,
            Integer releaseYear,
            Long locationId,
            String genreIds,
            String descriptorIds,
            String languages
    ) {
    }
}
