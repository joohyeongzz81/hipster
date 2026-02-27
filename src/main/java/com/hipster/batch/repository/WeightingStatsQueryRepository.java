package com.hipster.batch.repository;

import com.hipster.batch.dto.UserWeightingStatsDto;
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
}
