package com.hipster.chart.publish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishHistory;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.publish.domain.ChartValidationStatus;
import com.hipster.chart.publish.repository.ChartPublishHistoryRepository;
import com.hipster.chart.publish.repository.ChartPublishStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ChartPublishStateServiceTest {

    @Mock
    private ChartPublishStateRepository chartPublishStateRepository;

    @Mock
    private ChartPublishHistoryRepository chartPublishHistoryRepository;

    @Test
    @DisplayName("candidate publish 후 current/previous version이 기대대로 전이된다")
    void publishCandidate_transitionsCurrentAndPreviousVersion() {
        final ChartPublishProperties properties = new ChartPublishProperties();
        properties.setChartName("weekly_chart");

        final AtomicReference<ChartPublishState> stateRef = new AtomicReference<>();
        given(chartPublishStateRepository.findById(properties.getChartName()))
                .willAnswer(invocation -> Optional.ofNullable(stateRef.get()));
        given(chartPublishStateRepository.save(any(ChartPublishState.class)))
                .willAnswer(invocation -> {
                    final ChartPublishState state = invocation.getArgument(0);
                    stateRef.set(state);
                    return state;
                });
        given(chartPublishHistoryRepository.save(any(ChartPublishHistory.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        final ChartPublishStateService service = new ChartPublishStateService(
                properties,
                chartPublishStateRepository,
                chartPublishHistoryRepository,
                new ObjectMapper()
        );

        final LocalDateTime firstAsOf = LocalDateTime.of(2026, 3, 14, 10, 0);
        service.startCandidateGeneration("v20260314100000000", firstAsOf, "chart_scores_stage", "chart_scores_v20260314100000000");
        final ChartPublishState firstPublished = service.markPublished("v20260314100000000");

        assertThat(firstPublished.getCurrentVersion()).isEqualTo("v20260314100000000");
        assertThat(firstPublished.getPreviousVersion()).isNull();
        assertThat(firstPublished.getMysqlProjectionRef()).isEqualTo("chart_scores");
        assertThat(firstPublished.getEsIndexRef()).isEqualTo("chart_scores_v20260314100000000");
        assertThat(firstPublished.getLogicalAsOfAt()).isEqualTo(firstAsOf);

        final LocalDateTime secondAsOf = LocalDateTime.of(2026, 3, 14, 11, 0);
        service.startCandidateGeneration("v20260314110000000", secondAsOf, "chart_scores_stage", "chart_scores_v20260314110000000");
        final ChartPublishState secondPublished = service.markPublished("v20260314110000000");

        assertThat(secondPublished.getCurrentVersion()).isEqualTo("v20260314110000000");
        assertThat(secondPublished.getPreviousVersion()).isEqualTo("v20260314100000000");
        assertThat(secondPublished.getPreviousEsIndexRef()).isEqualTo("chart_scores_v20260314100000000");
        assertThat(secondPublished.getPreviousLogicalAsOfAt()).isEqualTo(firstAsOf);
    }

    @Test
    @DisplayName("validation 실패는 state에 FAILED validation status로 기록된다")
    void recordValidationResult_marksFailedValidationStatus() {
        final ChartPublishProperties properties = new ChartPublishProperties();
        properties.setChartName("weekly_chart");

        final ChartPublishState state = ChartPublishState.initialize(properties.getChartName());
        state.beginGeneration("v20260314120000000", "chart_scores_stage", "chart_scores_v20260314120000000", LocalDateTime.now());

        given(chartPublishStateRepository.findById(properties.getChartName()))
                .willReturn(Optional.of(state));
        given(chartPublishStateRepository.save(any(ChartPublishState.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(chartPublishHistoryRepository.save(any(ChartPublishHistory.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        final ChartPublishStateService service = new ChartPublishStateService(
                properties,
                chartPublishStateRepository,
                chartPublishHistoryRepository,
                new ObjectMapper()
        );

        service.recordValidationResult(
                "v20260314120000000",
                10L,
                0L,
                false,
                false,
                "{\"blockingPassed\":false}",
                LocalDateTime.now()
        );

        assertThat(state.getLastValidationStatus()).isEqualTo(ChartValidationStatus.FAILED);
    }
}
