package com.hipster.batch.calculator;

import com.hipster.batch.dto.UserWeightingStatsDto;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class WeightingCalculator {

    private static final int N_TARGET = 200;
    private static final double SIGMA_TARGET = 1.5;
    private static final double LAMBDA = 0.00095;
    private static final int M_TARGET = 20;
    private static final int L_TARGET = 100;

    public double calculateWeight(final UserWeightingStatsDto stats, final LocalDateTime userLastActiveDate) {
        if (stats == null || stats.ratingCount() < 10) {
            return 0.0;
        }

        final LocalDateTime lastActiveDate = stats.getLastActiveDate(userLastActiveDate);
        final LocalDateTime calculationDate = LocalDateTime.now();

        final double baseWeight = calculateBaseWeighting(stats, lastActiveDate, calculationDate);
        final double reviewBonus = calculateReviewBonus(stats);

        final double finalWeight = baseWeight * (1 + reviewBonus);
        return Math.max(0.0, Math.min(1.25, finalWeight));
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
