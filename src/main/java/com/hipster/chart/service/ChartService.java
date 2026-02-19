package com.hipster.chart.service;

import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.repository.ChartScoreRepository;
import com.hipster.global.exception.NotFoundException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartService {

    private final ReleaseRepository releaseRepository;
    private final RatingRepository ratingRepository;
    private final UserRepository userRepository;
    private final ChartScoreRepository chartScoreRepository;

    private static final double BAYESIAN_M = 50.0;
    private static final double GLOBAL_AVG_C = 3.2;
    private static final double ESOTERIC_THRESHOLD = 50.0;

    public Double calculateBayesianScore(Long releaseId) {
        List<Rating> ratings = ratingRepository.findByReleaseId(releaseId);
        if (ratings.isEmpty()) {
            return GLOBAL_AVG_C;
        }

        Set<Long> userIds = ratings.stream().map(Rating::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        double effectiveVotes = 0.0;
        double sumWeightedScores = 0.0;

        for (Rating rating : ratings) {
            User user = userMap.get(rating.getUserId());
            if (user != null && user.getWeightingScore() > 0) {
                double weight = user.getWeightingScore();
                effectiveVotes += weight;
                sumWeightedScores += rating.getScore() * weight;
            }
        }

        if (effectiveVotes == 0) {
            return GLOBAL_AVG_C;
        }

        double weightedAvgRating = sumWeightedScores / effectiveVotes;
        double bayesianScore = (effectiveVotes * weightedAvgRating + BAYESIAN_M * GLOBAL_AVG_C)
                / (effectiveVotes + BAYESIAN_M);

        return Math.round(bayesianScore * 100.0) / 100.0;
    }

    @Transactional
    public void updateAllChartScores() {
        log.info("Starting chart score update");
        LocalDateTime startTime = LocalDateTime.now();

        List<Release> releases = releaseRepository.findAllByPendingApprovalFalse();
        int processedCount = 0;

        for (Release release : releases) {
            try {
                List<Rating> ratings = ratingRepository.findByReleaseId(release.getId());
                
                Set<Long> userIds = ratings.stream().map(Rating::getUserId).collect(Collectors.toSet());
                Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

                double effectiveVotes = 0.0;
                double sumWeightedScores = 0.0;

                for (Rating rating : ratings) {
                    User user = userMap.get(rating.getUserId());
                    if (user != null && user.getWeightingScore() > 0) {
                        double weight = user.getWeightingScore();
                        effectiveVotes += weight;
                        sumWeightedScores += rating.getScore() * weight;
                    }
                }

                double weightedAvgRating = (effectiveVotes > 0) ? (sumWeightedScores / effectiveVotes) : 0.0;
                double bayesianScore;
                
                if (effectiveVotes == 0) {
                    bayesianScore = GLOBAL_AVG_C;
                } else {
                    bayesianScore = (effectiveVotes * weightedAvgRating + BAYESIAN_M * GLOBAL_AVG_C)
                            / (effectiveVotes + BAYESIAN_M);
                }
                
                bayesianScore = Math.round(bayesianScore * 100.0) / 100.0;


                ChartScore chartScore = chartScoreRepository.findByReleaseId(release.getId())
                        .orElse(new ChartScore());

                chartScore.setReleaseId(release.getId());
                chartScore.setBayesianScore(bayesianScore);
                chartScore.setWeightedAvgRating(weightedAvgRating);
                chartScore.setEffectiveVotes(effectiveVotes);
                chartScore.setTotalRatings((long) ratings.size());
                chartScore.setIsEsoteric(effectiveVotes < ESOTERIC_THRESHOLD);
                chartScore.setLastUpdated(LocalDateTime.now());

                chartScoreRepository.save(chartScore);

                processedCount++;

                if (processedCount % 1000 == 0) {
                    log.info("Processed {} releases", processedCount);
                }

            } catch (Exception e) {
                log.error("Failed to update chart score for release {}", release.getId(), e);
            }
        }

        LocalDateTime endTime = LocalDateTime.now();
        long durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime);

        log.info("Chart score update completed. Processed: {}, Duration: {} minutes",
                processedCount, durationMinutes);
    }
}
