package com.hipster.batch;

import com.hipster.user.domain.User;
import com.hipster.user.domain.UserWeightStats;
import com.hipster.user.repository.UserRepository;
import com.hipster.user.repository.UserWeightStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.hipster.batch.dto.UserWeightingStatsDto;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;


@Slf4j
@Service
@RequiredArgsConstructor
public class WeightingService {

    private static final int N_TARGET = 200;
    private static final double SIGMA_TARGET = 1.5;
    private static final double LAMBDA = 0.00095;
    private static final int M_TARGET = 20;
    private static final int L_TARGET = 100;

    private final UserRepository userRepository;
    private final UserWeightStatsRepository userWeightStatsRepository;
    private final JdbcTemplate jdbcTemplate;

    private String collectHeapSnapshot(final String label) {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long usedMB = heap.getUsed() / 1024 / 1024;
        final long maxMB = heap.getMax() / 1024 / 1024;
        return String.format("%-35s → %dMB / 최대 %dMB", label, usedMB, maxMB);
    }



    /**
     * Spring Batch Processor에서 호출하는 public 진입점 (To-Be)
     */
    public double calculateUserWeightingForBatch(final User user) {
        String sql = """
            SELECT 
                r_stats.ratingCount, 
                r_stats.ratingVariance, 
                r_stats.maxRatingDate,
                v_stats.reviewCount, 
                v_stats.reviewAvgLength, 
                v_stats.maxReviewDate
            FROM 
                (SELECT 
                    COUNT(*) AS ratingCount, 
                    COALESCE(VAR_POP(score), 0.0) AS ratingVariance, 
                    MAX(created_at) AS maxRatingDate 
                 FROM ratings 
                 WHERE user_id = ?) r_stats
            CROSS JOIN 
                (SELECT 
                    COUNT(*) AS reviewCount, 
                    COALESCE(AVG(LENGTH(content) - LENGTH(REPLACE(content, ' ', '')) + 1), 0.0) AS reviewAvgLength, 
                    MAX(created_at) AS maxReviewDate 
                 FROM reviews 
                 WHERE user_id = ?) v_stats
            """;

        UserWeightingStatsDto stats = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new UserWeightingStatsDto(
                rs.getLong("ratingCount"),
                rs.getDouble("ratingVariance"),
                rs.getObject("maxRatingDate", LocalDateTime.class),
                rs.getLong("reviewCount"),
                rs.getDouble("reviewAvgLength"),
                rs.getObject("maxReviewDate", LocalDateTime.class)
        ), user.getId(), user.getId());

        if (stats == null || stats.ratingCount() < 10) {
            return 0.0;
        }

        final LocalDateTime lastActiveDate = stats.getLastActiveDate(user.getLastActiveDate());
        final LocalDateTime calculationDate = LocalDateTime.now();

        final double baseWeight = calculateBaseWeighting(stats, lastActiveDate, calculationDate);
        final double reviewBonus = calculateReviewBonus(stats);

        final double finalWeight = baseWeight * (1 + reviewBonus);
        final double limitedWeight = Math.max(0.0, Math.min(1.25, finalWeight));

        // [Chapter 7] DB Aggregation 결과를 Summary Table(user_weight_stats)에 Upsert
        UserWeightStats weightStats = userWeightStatsRepository.findById(user.getId()).orElse(null);
        if (weightStats == null) {
            weightStats = UserWeightStats.builder()
                    .userId(user.getId())
                    .ratingCount(stats.ratingCount())
                    .ratingVariance(stats.ratingVariance())
                    .reviewCount(stats.reviewCount())
                    .reviewAvgLength(stats.reviewAvgLength())
                    .lastActiveDate(lastActiveDate)
                    .build();
            userWeightStatsRepository.save(weightStats);
        } else {
            weightStats.update(
                    stats.ratingCount(), 
                    stats.ratingVariance(), 
                    stats.reviewCount(), 
                    stats.reviewAvgLength(), 
                    lastActiveDate
            );
        }

        return limitedWeight;
    }


    private double calculateBaseWeighting(final UserWeightingStatsDto stats, final LocalDateTime lastActiveDate,
                                          final LocalDateTime calculationDate) {
        final long n = stats.ratingCount();
        final double wCount = 0.4 * Math.min(1.0, (double) n / N_TARGET);

        final double sigma = Math.sqrt(stats.ratingVariance());
        final double wDiversity = 0.4 * Math.min(1.0, sigma / SIGMA_TARGET);

        final long daysInactive = ChronoUnit.DAYS.between(lastActiveDate, calculationDate);
        final double wActivity = 0.2 * Math.exp(-LAMBDA * daysInactive);

        return wCount + wDiversity + wActivity;
    }

    private double calculateReviewBonus(final UserWeightingStatsDto stats) {
        if (stats.reviewCount() == 0) {
            return 0.0;
        }

        final long m = stats.reviewCount();
        final double bCount = 0.15 * Math.min(1.0, (double) m / M_TARGET);

        final double bQuality = 0.10 * Math.min(1.0, stats.reviewAvgLength() / L_TARGET);

        return Math.min(0.25, bCount + bQuality);
    }


}
