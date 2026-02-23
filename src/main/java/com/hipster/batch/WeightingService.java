package com.hipster.batch;

import com.hipster.rating.domain.Rating;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final RatingRepository ratingRepository;
    private final ReviewRepository reviewRepository;

    private String collectHeapSnapshot(final String label) {
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long usedMB = heap.getUsed() / 1024 / 1024;
        final long maxMB = heap.getMax() / 1024 / 1024;
        return String.format("%-35s → %dMB / 최대 %dMB", label, usedMB, maxMB);
    }

    private double calculateUserWeighting(final User user, final List<Rating> ratings,
                                          final List<Review> reviews, final LocalDateTime calculationDate) {
        if (ratings.size() < 10) {
            return 0.0;
        }

        final LocalDateTime lastActiveDate = getLastActiveDate(ratings, reviews, user);

        final double baseWeight = calculateBaseWeighting(ratings, lastActiveDate, calculationDate);
        final double reviewBonus = calculateReviewBonus(reviews);

        final double finalWeight = baseWeight * (1 + reviewBonus);

        return Math.max(0.0, Math.min(1.25, finalWeight));
    }

    /**
     * Spring Batch Processor에서 호출하는 public 진입점
     */
    public double calculateUserWeightingForBatch(final User user, final List<Rating> ratings,
                                                 final List<Review> reviews) {
        return calculateUserWeighting(user, ratings, reviews, LocalDateTime.now());
    }

    private LocalDateTime getLastActiveDate(final List<Rating> ratings, final List<Review> reviews,
                                            final User user) {
        final Optional<LocalDateTime> maxRatingDate = ratings.stream()
                .map(Rating::getCreatedAt)
                .max(Comparator.naturalOrder());

        final Optional<LocalDateTime> maxReviewDate = reviews.stream()
                .map(Review::getCreatedAt)
                .max(Comparator.naturalOrder());

        return Stream.of(maxRatingDate, maxReviewDate)
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder())
                .orElseGet(() -> Optional.ofNullable(user.getLastActiveDate())
                        .orElse(LocalDateTime.now()));
    }

    private double calculateBaseWeighting(final List<Rating> ratings, final LocalDateTime lastActiveDate,
                                          final LocalDateTime calculationDate) {
        final int n = ratings.size();
        final double wCount = 0.4 * Math.min(1.0, (double) n / N_TARGET);

        final double[] scores = ratings.stream()
                .mapToDouble(Rating::getScore)
                .toArray();
        final double mean = Arrays.stream(scores).average().orElse(0.0);
        final double variance = Arrays.stream(scores)
                .map(s -> Math.pow(s - mean, 2))
                .average()
                .orElse(0.0);
        final double sigma = Math.sqrt(variance);
        final double wDiversity = 0.4 * Math.min(1.0, sigma / SIGMA_TARGET);

        final long daysInactive = ChronoUnit.DAYS.between(lastActiveDate, calculationDate);
        final double wActivity = 0.2 * Math.exp(-LAMBDA * daysInactive);

        return wCount + wDiversity + wActivity;
    }

    private double calculateReviewBonus(final List<Review> reviews) {
        if (reviews.isEmpty()) {
            return 0.0;
        }

        final int m = reviews.size();
        final double bCount = 0.15 * Math.min(1.0, (double) m / M_TARGET);

        final double avgLength = reviews.stream()
                .mapToInt(r -> r.getContent().split("\\s+").length)
                .average()
                .orElse(0.0);
        final double bQuality = 0.10 * Math.min(1.0, avgLength / L_TARGET);

        return Math.min(0.25, bCount + bQuality);
    }
}
