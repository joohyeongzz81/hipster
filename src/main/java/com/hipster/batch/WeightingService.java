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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

    @Transactional
    public void recalculateWeightings() {
        log.info("Starting user weighting recalculation batch job.");
        PageRequest pageRequest = PageRequest.of(0, 100);
        Page<User> userPage;

        do {
            userPage = userRepository.findAll(pageRequest);
            userPage.getContent().forEach(user -> {
                double newWeight = calculateUserWeighting(user.getId(), LocalDateTime.now());
                user.updateWeightingScore(newWeight);
            });
            pageRequest = pageRequest.next();
        } while (userPage.hasNext());

        log.info("Finished user weighting recalculation batch job.");
    }

    private Double calculateUserWeighting(Long userId, LocalDateTime calculationDate) {
        List<Rating> ratings = ratingRepository.findByUserId(userId);
        if (ratings.size() < 10) {
            return 0.0;
        }

        List<Review> reviews = reviewRepository.findByUserId(userId);
        LocalDateTime lastActiveDate = getLastActiveDate(ratings, reviews, userId);

        double baseWeight = calculateBaseWeighting(ratings, lastActiveDate, calculationDate);
        double reviewBonus = calculateReviewBonus(reviews);

        double finalWeight = baseWeight * (1 + reviewBonus);

        return Math.max(0.0, Math.min(1.25, finalWeight));
    }

    private LocalDateTime getLastActiveDate(List<Rating> ratings, List<Review> reviews, Long userId) {
        Optional<LocalDateTime> maxRatingDate = ratings.stream()
                .map(Rating::getCreatedAt)
                .max(Comparator.naturalOrder());

        Optional<LocalDateTime> maxReviewDate = reviews.stream()
                .map(Review::getCreatedAt)
                .max(Comparator.naturalOrder());

        if (maxRatingDate.isPresent() && maxReviewDate.isPresent()) {
            return maxRatingDate.get().isAfter(maxReviewDate.get()) ? maxRatingDate.get() : maxReviewDate.get();
        } else if (maxRatingDate.isPresent()) {
            return maxRatingDate.get();
        } else if (maxReviewDate.isPresent()) {
            return maxReviewDate.get();
        } else {
            // Fallback to user's last active date if no ratings or reviews
            return userRepository.findById(userId).map(User::getLastActiveDate).orElse(LocalDateTime.now());
        }
    }

    private double calculateBaseWeighting(
            List<Rating> ratings,
            LocalDateTime lastActiveDate,
            LocalDateTime calculationDate
    ) {
        int n = ratings.size();
        double wCount = 0.4 * Math.min(1.0, (double) n / N_TARGET);

        double[] scores = ratings.stream()
                .mapToDouble(Rating::getScore)
                .toArray();
        double mean = Arrays.stream(scores).average().orElse(0.0);
        double variance = Arrays.stream(scores)
                .map(s -> Math.pow(s - mean, 2))
                .average()
                .orElse(0.0);
        double sigma = Math.sqrt(variance);
        double wDiversity = 0.4 * Math.min(1.0, sigma / SIGMA_TARGET);

        long daysInactive = ChronoUnit.DAYS.between(lastActiveDate, calculationDate);
        double wActivity = 0.2 * Math.exp(-LAMBDA * daysInactive);

        return wCount + wDiversity + wActivity;
    }

    private double calculateReviewBonus(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return 0.0;
        }

        int m = reviews.size();
        double bCount = 0.15 * Math.min(1.0, (double) m / M_TARGET);

        double avgLength = reviews.stream()
                .mapToInt(r -> r.getContent().split("\s+").length)
                .average()
                .orElse(0.0);
        double bQuality = 0.10 * Math.min(1.0, avgLength / L_TARGET);

        return Math.min(0.25, bCount + bQuality);
    }
}
