package com.hipster.chart.publish.service;

import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Set;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChartPublishOrchestratorServiceTest {

    @Mock
    private ReleaseRatingSummaryRepository releaseRatingSummaryRepository;

    @Mock
    private ChartScoreQueryRepository chartScoreQueryRepository;

    @Mock
    private ChartElasticsearchIndexService chartElasticsearchIndexService;

    @Mock
    private ChartPublishStateService chartPublishStateService;

    @Mock
    private ChartPublishedVersionService chartPublishedVersionService;

    @Mock
    private ChartLastUpdatedService chartLastUpdatedService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("publish 성공 시 projection, alias, metadata가 published version 기준으로 반영된다")
    void publishVersion_updatesProjectionAliasAndMetadataInOrder() {
        final ChartPublishProperties properties = new ChartPublishProperties();
        properties.setEnabled(true);
        properties.setChartName("weekly_chart");

        final String version = "v20260314153000000";
        final LocalDateTime logicalAsOfAt = LocalDateTime.of(2026, 3, 14, 15, 0);

        final ChartPublishState publishingState = ChartPublishState.initialize("weekly_chart");
        publishingState.beginGeneration(version, "chart_scores_stage", "chart_scores_bench_v20260314153000000", logicalAsOfAt);

        final ChartPublishState publishedState = ChartPublishState.initialize("weekly_chart");
        publishedState.beginGeneration(version, "chart_scores_stage", "chart_scores_bench_v20260314153000000", logicalAsOfAt);
        publishedState.publishCandidate(version, "chart_scores", LocalDateTime.of(2026, 3, 14, 15, 30));

        given(chartPublishStateService.markPublishing(version)).willReturn(publishingState);
        given(chartPublishStateService.markPublished(version)).willReturn(publishedState);
        given(redisTemplate.keys("chart:v1:*")).willReturn(Set.of("chart:v1:legacy:all:page:0"));

        final ChartPublishOrchestratorService service = new ChartPublishOrchestratorService(
                properties,
                releaseRatingSummaryRepository,
                chartScoreQueryRepository,
                chartElasticsearchIndexService,
                chartPublishStateService,
                chartPublishedVersionService,
                chartLastUpdatedService,
                redisTemplate
        );

        service.publishVersion(version);

        verify(chartScoreQueryRepository).publishStageTable();
        verify(chartElasticsearchIndexService).publishCandidateAlias(version);
        verify(chartPublishStateService).markPublished(version);
        verify(chartPublishedVersionService).cachePublishedVersion(version);
        verify(redisTemplate).delete(Set.of("chart:v1:legacy:all:page:0"));
        verify(chartLastUpdatedService).cacheLastUpdated(logicalAsOfAt);
    }
}
