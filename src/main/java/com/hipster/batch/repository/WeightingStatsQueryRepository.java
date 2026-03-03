package com.hipster.batch.repository;

import com.hipster.batch.dto.UserWeightingStatsDto;
import com.hipster.rating.BayesianConstants;
import com.hipster.user.domain.UserWeightStats;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class WeightingStatsQueryRepository {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final JdbcTemplate jdbcTemplate;

    public Map<Long, UserWeightingStatsDto> findStatsByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return new HashMap<>();

        String sql = """
            SELECT 
                u.id AS user_id,
                COALESCE(r_stats.ratingCount, 0) AS ratingCount, 
                COALESCE(r_stats.ratingVariance, 0.0) AS ratingVariance, 
                r_stats.maxRatingDate,
                COALESCE(v_stats.reviewCount, 0) AS reviewCount, 
                COALESCE(v_stats.reviewAvgLength, 0.0) AS reviewAvgLength, 
                v_stats.maxReviewDate
            FROM users u
            LEFT JOIN (
                SELECT user_id, COUNT(*) AS ratingCount, COALESCE(VAR_POP(score), 0.0) AS ratingVariance, MAX(created_at) AS maxRatingDate 
                FROM ratings 
                WHERE user_id IN (:userIds) 
                GROUP BY user_id
            ) r_stats ON u.id = r_stats.user_id
            LEFT JOIN (
                SELECT user_id, COUNT(*) AS reviewCount, COALESCE(AVG(LENGTH(content) - LENGTH(REPLACE(content, ' ', '')) + 1), 0.0) AS reviewAvgLength, MAX(created_at) AS maxReviewDate 
                FROM reviews 
                WHERE user_id IN (:userIds) 
                GROUP BY user_id
            ) v_stats ON u.id = v_stats.user_id
            WHERE u.id IN (:userIds)
            """;

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        parameters.addValue("userIds", userIds);

        Map<Long, UserWeightingStatsDto> resultMap = new HashMap<>();
        namedParameterJdbcTemplate.query(sql, parameters, rs -> {
            Long userId = rs.getLong("user_id");
            Timestamp maxRatingTs = rs.getTimestamp("maxRatingDate");
            Timestamp maxReviewTs = rs.getTimestamp("maxReviewDate");
            
            resultMap.put(userId, new UserWeightingStatsDto(
                    rs.getLong("ratingCount"),
                    rs.getDouble("ratingVariance"),
                    maxRatingTs != null ? maxRatingTs.toLocalDateTime() : null,
                    rs.getLong("reviewCount"),
                    rs.getDouble("reviewAvgLength"),
                    maxReviewTs != null ? maxReviewTs.toLocalDateTime() : null
            ));
        });

        return resultMap;
    }

    public void bulkUpsertUserWeightStats(List<UserWeightStats> statsList) {
        if (statsList == null || statsList.isEmpty()) return;

        String sql = """
            INSERT INTO user_weight_stats (user_id, rating_count, rating_variance, review_count, review_avg_length, last_active_date, last_calculated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE 
                rating_count = VALUES(rating_count),
                rating_variance = VALUES(rating_variance),
                review_count = VALUES(review_count),
                review_avg_length = VALUES(review_avg_length),
                last_active_date = VALUES(last_active_date),
                last_calculated_at = CURRENT_TIMESTAMP
            """;

        jdbcTemplate.batchUpdate(sql, statsList, statsList.size(), (ps, stats) -> {
            ps.setLong(1, stats.getUserId());
            ps.setLong(2, stats.getRatingCount());
            ps.setDouble(3, stats.getRatingVariance());
            ps.setLong(4, stats.getReviewCount());
            ps.setDouble(5, stats.getReviewAvgLength());
            if (stats.getLastActiveDate() != null) {
                ps.setTimestamp(6, Timestamp.valueOf(stats.getLastActiveDate()));
            } else {
                ps.setNull(6, java.sql.Types.TIMESTAMP);
            }
        });
    }

    /**
     * users와 user_weight_stats를 비교하여 가중치가 실제로 변경된 유저 ID 목록을 반환.
     * user_weight_stats는 배치 직전 스냅샷이므로 diff 감지에 사용 가능.
     */
    public List<Long> findChangedUserIds() {
        String sql = """
            SELECT u.id
            FROM users u
            JOIN user_weight_stats ws ON u.id = ws.user_id
            WHERE u.weighting_score <> ws.weighting_score
            """;
        return jdbcTemplate.queryForList(sql, Long.class);
    }

    /**
     * 변경된 유저들이 평점을 남긴 앨범만 타겟으로 release_rating_summary를 최신 weightingScore 기준으로 재집계.
     * ratings 테이블은 읽기 전용(Source of Truth)으로만 사용.
     * IN 절 폭발 방지를 위해 1000건 단위로 파티셔닝하여 처리.
     */
    public void reconcileAffectedReleases(final List<Long> changedUserIds) {
        if (changedUserIds == null || changedUserIds.isEmpty()) {
            return;
        }

        // IN 절 폭발 방지: 1000건 단위 파티셔닝
        final int partitionSize = 1000;
        for (int i = 0; i < changedUserIds.size(); i += partitionSize) {
            final List<Long> partition = changedUserIds.subList(i, Math.min(i + partitionSize, changedUserIds.size()));
            reconcilePartition(partition);
        }
    }

    private void reconcilePartition(final List<Long> userIds) {
        String sql = """
            INSERT INTO release_rating_summary (release_id, weighted_score_sum, weighted_count_sum, bayesian_score, updated_at)
            SELECT
                r.release_id,
                SUM(r.score * u.weighting_score)                                                  AS weighted_score_sum,
                SUM(u.weighting_score)                                                            AS weighted_count_sum,
                (:c * :m + SUM(r.score * u.weighting_score)) / (:c + SUM(u.weighting_score))     AS bayesian_score,
                NOW()
            FROM ratings r
            JOIN users u ON r.user_id = u.id
            WHERE r.release_id IN (
                SELECT DISTINCT release_id
                FROM ratings
                WHERE user_id IN (:userIds)
            )
            GROUP BY r.release_id
            ON DUPLICATE KEY UPDATE
                weighted_score_sum = VALUES(weighted_score_sum),
                weighted_count_sum = VALUES(weighted_count_sum),
                bayesian_score     = VALUES(bayesian_score),
                updated_at         = VALUES(updated_at)
            """;

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("userIds", userIds);
        params.addValue("m", BayesianConstants.M);
        params.addValue("c", BayesianConstants.C);
        namedParameterJdbcTemplate.update(sql, params);
    }
}
