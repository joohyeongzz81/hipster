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
import com.hipster.release.domain.Release;
import com.hipster.release.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartService {

    private final ReleaseRepository releaseRepository;
    private final ChartScoreRepository chartScoreRepository;
    private final ArtistRepository artistRepository;

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
}
