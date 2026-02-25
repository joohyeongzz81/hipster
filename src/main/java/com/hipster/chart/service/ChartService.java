package com.hipster.chart.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.dto.response.ChartEntryResponse;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.chart.dto.response.TopChartResponse;
import com.hipster.chart.repository.ChartScoreRepository;
import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseStatus;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    private final ArtistRepository artistRepository;

    private static final double BAYESIAN_M = 50.0;
    private static final double GLOBAL_AVG_C = 3.2;
    private static final double ESOTERIC_THRESHOLD = 50.0;

    public Double calculateBayesianScore(final Long releaseId) {
        final List<Rating> ratings = ratingRepository.findByReleaseId(releaseId);
        if (ratings.isEmpty()) {
            return GLOBAL_AVG_C;
        }

        final ScoreData scoreData = calculateScoreData(ratings);

        if (scoreData.effectiveVotes() == 0) {
            return GLOBAL_AVG_C;
        }

        return computeBayesianScore(scoreData.weightedAvgRating(), scoreData.effectiveVotes());
    }

    @Transactional
    public void updateAllChartScores() {
        log.info("Starting chart score update");
        final LocalDateTime startTime = LocalDateTime.now();

        final List<Release> releases = releaseRepository.findAllByStatus(ReleaseStatus.ACTIVE);
        int processedCount = 0;

        for (final Release release : releases) {
            try {
                processReleaseChartScore(release);
                processedCount++;

                if (processedCount % 1000 == 0) {
                    log.info("Processed {} releases", processedCount);
                }
            } catch (Exception e) {
                log.error("Failed to update chart score for release {}", release.getId(), e);
            }
        }

        final LocalDateTime endTime = LocalDateTime.now();
        final long durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime);

        log.info("Chart score update completed. Processed: {}, Duration: {} minutes",
                processedCount, durationMinutes);
    }

    @Transactional(readOnly = true)
    public TopChartResponse getTopChart(final Integer limit, final ChartFilterRequest filter) {
        validateLimit(limit);
        log.debug("Fetching top {} chart from DB with filter: {}", limit, filter);

        final Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "bayesianScore"));
        final List<ChartScore> chartScores = fetchChartScores(filter, pageable);

        final List<ChartEntryResponse> entries = buildChartEntries(chartScores);

        final LocalDateTime lastUpdated = chartScoreRepository.findFirstByOrderByLastUpdatedDesc()
                .map(ChartScore::getLastUpdated)
                .orElse(LocalDateTime.now());

        final String chartTitle = buildChartTitle(limit, filter);

        return TopChartResponse.builder()
                .chartType(chartTitle)
                .lastUpdated(lastUpdated)
                .entries(entries)
                .build();
    }

    private void validateLimit(final int limit) {
        if (limit < 10 || limit > 1000) {
            throw new BadRequestException(ErrorCode.INVALID_CHART_LIMIT);
        }
    }

    private void processReleaseChartScore(final Release release) {
        final List<Rating> ratings = ratingRepository.findByReleaseId(release.getId());
        final ScoreData scoreData = calculateScoreData(ratings);

        final double bayesianScore = (scoreData.effectiveVotes() == 0)
                ? GLOBAL_AVG_C
                : computeBayesianScore(scoreData.weightedAvgRating(), scoreData.effectiveVotes());

        final double roundedScore = Math.round(bayesianScore * 100.0) / 100.0;

        final ChartScore chartScore = chartScoreRepository.findByReleaseId(release.getId())
                .orElseGet(() -> new ChartScore(release.getId()));

        chartScore.updateScore(
                roundedScore,
                scoreData.weightedAvgRating(),
                scoreData.effectiveVotes(),
                (long) scoreData.totalRatings(),
                scoreData.effectiveVotes() < ESOTERIC_THRESHOLD
        );

        chartScoreRepository.save(chartScore);
    }

    private ScoreData calculateScoreData(final List<Rating> ratings) {
        if (ratings.isEmpty()) {
            return new ScoreData(0.0, 0.0, 0);
        }

        final Set<Long> userIds = ratings.stream()
                .map(Rating::getUserId)
                .collect(Collectors.toSet());
        final Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        double effectiveVotes = 0.0;
        double sumWeightedScores = 0.0;

        for (final Rating rating : ratings) {
            final User user = userMap.get(rating.getUserId());
            if (user != null && user.getWeightingScore() > 0) {
                final double weight = user.getWeightingScore();
                effectiveVotes += weight;
                sumWeightedScores += rating.getScore() * weight;
            }
        }

        final double weightedAvgRating = (effectiveVotes > 0) ? (sumWeightedScores / effectiveVotes) : 0.0;

        return new ScoreData(effectiveVotes, weightedAvgRating, ratings.size());
    }

    private double computeBayesianScore(final double weightedAvgRating, final double effectiveVotes) {
        final double score = (effectiveVotes * weightedAvgRating + BAYESIAN_M * GLOBAL_AVG_C)
                / (effectiveVotes + BAYESIAN_M);
        return Math.round(score * 100.0) / 100.0;
    }

    private List<ChartScore> fetchChartScores(final ChartFilterRequest filter, final Pageable pageable) {
        if (filter == null) {
            return chartScoreRepository.findCharts(null, null, null, false, pageable);
        }
        return chartScoreRepository.findCharts(
                filter.genreId(),
                filter.year(),
                filter.releaseType(),
                Boolean.TRUE.equals(filter.includeEsoteric()),
                pageable
        );
    }

    private List<ChartEntryResponse> buildChartEntries(final List<ChartScore> chartScores) {
        final List<ChartEntryResponse> entries = new ArrayList<>();
        long rank = 1;

        for (final ChartScore score : chartScores) {
            final Release release = releaseRepository.findById(score.getReleaseId())
                    .orElse(null);

            if (release != null) {
                final String artistName = artistRepository.findById(release.getArtistId())
                        .map(Artist::getName)
                        .orElse("Unknown Artist");

                final ChartEntryResponse entry = ChartEntryResponse.builder()
                        .rank(rank++)
                        .releaseId(score.getReleaseId())
                        .title(release.getTitle())
                        .artistName(artistName)
                        .releaseYear(release.getReleaseDate().getYear())
                        .bayesianScore(score.getBayesianScore())
                        .weightedAvgRating(score.getWeightedAvgRating())
                        .totalRatings(score.getTotalRatings())
                        .isEsoteric(score.getIsEsoteric())
                        .build();

                entries.add(entry);
            }
        }
        return entries;
    }

    private String buildChartTitle(final int limit, final ChartFilterRequest filter) {
        final StringBuilder title = new StringBuilder("Top " + limit + " Releases");
        if (filter != null) {
            if (filter.genreId() != null) title.append(" (Genre ").append(filter.genreId()).append(")");
            if (filter.year() != null) title.append(" (").append(filter.year()).append(")");
            if (filter.releaseType() != null) title.append(" [").append(filter.releaseType()).append("]");
        }
        return title.toString();
    }

    private record ScoreData(double effectiveVotes, double weightedAvgRating, int totalRatings) {
    }
}
