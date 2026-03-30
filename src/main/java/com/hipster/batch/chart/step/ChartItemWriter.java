package com.hipster.batch.chart.step;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartReleaseMetadataQueryRepository;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChartItemWriter implements ItemWriter<ChartScoreDto> {

    private final ChartScoreQueryRepository chartScoreQueryRepository;
    private final ChartReleaseMetadataQueryRepository chartReleaseMetadataQueryRepository;

    @Override
    public void write(final @NonNull Chunk<? extends ChartScoreDto> chunk) {
        writeWithBreakdown(chunk);
    }

    public WriteBreakdown writeWithBreakdown(final @NonNull Chunk<? extends ChartScoreDto> chunk) {
        final List<ChartScoreDto> items = new ArrayList<>(chunk.getItems());
        if (items.isEmpty()) {
            return new WriteBreakdown(0, 0, 0, 0);
        }

        final List<Long> releaseIds = items.stream()
                .map(ChartScoreDto::releaseId)
                .toList();

        final long metadataFetchStart = System.nanoTime();
        final Map<Long, ChartReleaseMetadataQueryRepository.ChartReleaseMetadata> releaseMetadataMap =
                chartReleaseMetadataQueryRepository.findMetadataByReleaseIds(releaseIds);
        final long metadataFetchMillis = Duration.ofNanos(System.nanoTime() - metadataFetchStart).toMillis();

        final long serializationStart = System.nanoTime();
        final List<ChartScoreDto> enrichedItems = items.stream()
                .map(dto -> enrich(dto, releaseMetadataMap))
                .toList();
        final long serializationMillis = Duration.ofNanos(System.nanoTime() - serializationStart).toMillis();

        final long upsertStart = System.nanoTime();
        chartScoreQueryRepository.bulkUpsertPublishStageChartScores(enrichedItems);
        final long upsertMillis = Duration.ofNanos(System.nanoTime() - upsertStart).toMillis();

        log.debug("[CHART BATCH] chunk={} metadata={}ms serialization={}ms upsert={}ms",
                enrichedItems.size(), metadataFetchMillis, serializationMillis, upsertMillis);

        return new WriteBreakdown(
                enrichedItems.size(),
                metadataFetchMillis,
                serializationMillis,
                upsertMillis
        );
    }

    private ChartScoreDto enrich(final ChartScoreDto dto,
                                 final Map<Long, ChartReleaseMetadataQueryRepository.ChartReleaseMetadata> releaseMetadataMap) {
        final ChartReleaseMetadataQueryRepository.ChartReleaseMetadata metadata = releaseMetadataMap.get(dto.releaseId());
        if (metadata == null) {
            return dto;
        }

        return new ChartScoreDto(
                dto.releaseId(),
                dto.bayesianScore(),
                dto.weightedAvgRating(),
                dto.effectiveVotes(),
                dto.totalRatings(),
                dto.isEsoteric(),
                metadata.genreIds(),
                metadata.releaseType(),
                metadata.releaseYear(),
                metadata.descriptorIds(),
                metadata.locationId(),
                metadata.languages()
        );
    }

    public record WriteBreakdown(
            int itemCount,
            long metadataFetchMillis,
            long serializationMillis,
            long upsertMillis
    ) {
        public long totalMillis() {
            return metadataFetchMillis + serializationMillis + upsertMillis;
        }
    }
}
