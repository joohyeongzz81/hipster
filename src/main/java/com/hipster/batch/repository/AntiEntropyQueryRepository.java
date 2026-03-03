package com.hipster.batch.repository;

import com.hipster.rating.BayesianConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Anti-Entropy Full 배치 전용 레포지토리.
 *
 * ratings JOIN users를 Source of Truth로 삼아 release_rating_summary를 전체 재집계합니다.
 * Chunk 단위(1000건) 페이징으로 Master DB 부하를 분산합니다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AntiEntropyQueryRepository {

    private static final int CHUNK_SIZE = 1000;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /**
     * 대상 release_id 목록을 조회합니다. (ratings에 평점이 있는 앨범만)
     */
    public List<Long> findAllReleaseIds() {
        String sql = "SELECT DISTINCT release_id FROM ratings ORDER BY release_id";
        return namedParameterJdbcTemplate.queryForList(sql, new MapSqlParameterSource(), Long.class);
    }

    /**
     * 주어진 release_id 목록에 대해 ratings JOIN users 전체 재집계를 수행합니다.
     * ON DUPLICATE KEY UPDATE로 멱등하게 덮어씁니다.
     *
     * @param releaseIds  이번 청크에서 처리할 release_id 목록
     * @param batchSyncedAt 배치 시작 시각 (batch_synced_at 기록용)
     */
    public void reconcileChunk(final List<Long> releaseIds, final LocalDateTime batchSyncedAt) {
        if (releaseIds == null || releaseIds.isEmpty()) return;

        String sql = """
            INSERT INTO release_rating_summary
                (release_id, weighted_score_sum, weighted_count_sum,
                 bayesian_score, total_rating_count, average_score, batch_synced_at)
            SELECT
                r.release_id,
                SUM(r.score * u.weighting_score)                                               AS weighted_score_sum,
                SUM(u.weighting_score)                                                         AS weighted_count_sum,
                (:c * :m + SUM(r.score * u.weighting_score)) / (:c + SUM(u.weighting_score))  AS bayesian_score,
                COUNT(*)                                                                       AS total_rating_count,
                AVG(r.score)                                                                   AS average_score,
                :batchSyncedAt
            FROM ratings r
            JOIN users u ON r.user_id = u.id
            WHERE r.release_id IN (:releaseIds)
            GROUP BY r.release_id
            ON DUPLICATE KEY UPDATE
                weighted_score_sum = VALUES(weighted_score_sum),
                weighted_count_sum = VALUES(weighted_count_sum),
                bayesian_score     = VALUES(bayesian_score),
                total_rating_count = VALUES(total_rating_count),
                average_score      = VALUES(average_score),
                batch_synced_at    = VALUES(batch_synced_at)
            """;

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("releaseIds", releaseIds);
        params.addValue("m", BayesianConstants.M);
        params.addValue("c", BayesianConstants.C);
        params.addValue("batchSyncedAt", batchSyncedAt);

        namedParameterJdbcTemplate.update(sql, params);
    }

    /**
     * release_id 목록을 CHUNK_SIZE 단위로 분할합니다.
     */
    public static List<List<Long>> partition(final List<Long> list) {
        final List<List<Long>> partitions = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK_SIZE) {
            partitions.add(list.subList(i, Math.min(i + CHUNK_SIZE, list.size())));
        }
        return partitions;
    }
}
