package com.hipster.chart.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.dto.response.ChartEntryResponse;
import com.hipster.chart.dto.response.TopChartResponse;
import com.hipster.release.domain.Release;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChartResponseAssembler {

    private final ArtistRepository artistRepository;

    public TopChartResponse assemble(final String chartType,
                                     final String version,
                                     final LocalDateTime lastUpdated,
                                     final List<ChartScore> chartScores) {
        return TopChartResponse.builder()
                .chartType(chartType)
                .version(version)
                .lastUpdated(lastUpdated)
                .entries(buildChartEntries(chartScores))
                .build();
    }

    private List<ChartEntryResponse> buildChartEntries(final List<ChartScore> chartScores) {
        final Map<Long, String> artistNamesByArtistId = loadArtistNames(chartScores);
        final List<ChartEntryResponse> entries = new ArrayList<>();
        long rank = 1;

        for (final ChartScore score : chartScores) {
            final Release release = score.getRelease();
            if (release == null) {
                continue;
            }

            final String artistName = artistNamesByArtistId.getOrDefault(release.getArtistId(), "Unknown Artist");
            final Integer releaseYear = release.getReleaseDate() != null
                    ? release.getReleaseDate().getYear()
                    : score.getReleaseYear();

            entries.add(ChartEntryResponse.builder()
                    .rank(rank++)
                    .releaseId(score.getReleaseId())
                    .title(release.getTitle())
                    .artistName(artistName)
                    .releaseYear(releaseYear)
                    .bayesianScore(score.getBayesianScore())
                    .weightedAvgRating(score.getWeightedAvgRating())
                    .totalRatings(score.getTotalRatings())
                    .isEsoteric(score.getIsEsoteric())
                    .build());
        }

        return entries;
    }

    private Map<Long, String> loadArtistNames(final List<ChartScore> chartScores) {
        final List<Long> artistIds = chartScores.stream()
                .map(ChartScore::getRelease)
                .filter(release -> release != null && release.getArtistId() != null)
                .map(Release::getArtistId)
                .distinct()
                .toList();

        final Map<Long, String> artistNamesByArtistId = new LinkedHashMap<>();
        for (Artist artist : artistRepository.findAllById(artistIds)) {
            artistNamesByArtistId.put(artist.getId(), artist.getName());
        }
        return artistNamesByArtistId;
    }
}
