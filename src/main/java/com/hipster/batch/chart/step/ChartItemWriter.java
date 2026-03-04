package com.hipster.batch.chart.step;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import org.springframework.lang.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 청크 단위로 ChartScoreDto 목록을 받아 chart_scores 테이블에 Bulk UPSERT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartItemWriter implements ItemWriter<ChartScoreDto> {

    private final ChartScoreQueryRepository chartScoreQueryRepository;

    @Override
    public void write(final @NonNull Chunk<? extends ChartScoreDto> chunk) {
        final List<ChartScoreDto> items = new ArrayList<>(chunk.getItems());
        if (items.isEmpty()) return;

        chartScoreQueryRepository.bulkUpsertChartScores(items);
        log.debug("[CHART BATCH] 청크 {} 건 UPSERT 완료", items.size());
    }
}

