package com.hipster.chart.publish.service;

import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartPublishOrchestratorService {

    public static final String CANDIDATE_VERSION_KEY = "chartPublishCandidateVersion";
    public static final String CANDIDATE_LOGICAL_AS_OF_KEY = "chartPublishLogicalAsOfAt";

    private final ChartPublishProperties chartPublishProperties;
    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;
    private final ChartScoreQueryRepository chartScoreQueryRepository;
    private final ChartElasticsearchIndexService chartElasticsearchIndexService;
    private final ChartPublishStateService chartPublishStateService;
    private final ChartPublishedVersionService chartPublishedVersionService;
    private final ChartLastUpdatedService chartLastUpdatedService;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public ChartPublishStateService.CandidateVersionContext generateCandidateVersion() {
        final LocalDateTime logicalAsOfAt = resolveLogicalAsOfAt();
        final String candidateVersion = chartPublishStateService.generateNextVersion();
        final String candidateEsIndex = chartElasticsearchIndexService.buildCandidateIndexName(candidateVersion);

        chartScoreQueryRepository.preparePublishStageTable();

        return chartPublishStateService.startCandidateGeneration(
                candidateVersion,
                logicalAsOfAt,
                chartPublishProperties.getStageTableName(),
                candidateEsIndex
        );
    }

    @Transactional
    public ChartPublishStateService.ValidationGateResult validateCandidateVersion(final String version) {
        chartPublishStateService.markValidating(version);
        final var state = chartPublishStateService.requireState();

        final long mysqlRowCount = chartScoreQueryRepository.countPublishStageRows();
        final long esDocCount = chartElasticsearchIndexService.countDocumentsByVersion(version);
        final boolean searchable = chartElasticsearchIndexService.isIndexSearchableByVersion(version);
        final boolean blockingPassed = mysqlRowCount > 0 && mysqlRowCount == esDocCount && searchable;

        return chartPublishStateService.recordValidationResult(
                version,
                mysqlRowCount,
                esDocCount,
                searchable,
                blockingPassed,
                chartPublishStateService.validationSummaryJson(mysqlRowCount, esDocCount, searchable, blockingPassed),
                state.getCandidateLogicalAsOfAt()
        );
    }

    @Transactional
    public void publishVersion(final String version) {
        boolean projectionPublished = false;
        boolean aliasPublished = false;

        try {
            chartPublishStateService.markPublishing(version);

            chartScoreQueryRepository.publishStageTable();
            projectionPublished = true;

            chartElasticsearchIndexService.publishCandidateAlias(version);
            aliasPublished = true;

            final var publishedState = chartPublishStateService.markPublished(version);
            chartPublishedVersionService.cachePublishedVersion(publishedState.getCurrentVersion());
            evictLegacyChartCache();
            chartLastUpdatedService.cacheLastUpdated(publishedState.getLogicalAsOfAt());
        } catch (Exception e) {
            log.error("[ChartPublish] publish failed. version={}", version, e);
            chartPublishStateService.markFailed("PUBLISH_FAILED", e.getMessage());
            if (projectionPublished || aliasPublished) {
                rollbackToPreviousVersion("AUTO_ROLLBACK_AFTER_PUBLISH_FAILURE");
            }
            throw new IllegalStateException("Chart publish failed for version=" + version, e);
        }
    }

    @Transactional
    public void rollbackToPreviousVersion(final String reason) {
        final var state = chartPublishStateService.requireState();
        if (state.getPreviousVersion() == null) {
            log.warn("[ChartPublish] rollback skipped. previousVersion is null");
            return;
        }

        chartScoreQueryRepository.rollbackPublishedTable();
        if (state.getPreviousEsIndexRef() != null) {
            chartElasticsearchIndexService.rollbackAliasToIndex(state.getPreviousEsIndexRef());
        }
        final var rolledBackState = chartPublishStateService.markRolledBack(state.getCurrentVersion(), reason);
        chartPublishedVersionService.cachePublishedVersion(rolledBackState.getCurrentVersion());
        evictLegacyChartCache();
        if (rolledBackState.getLogicalAsOfAt() != null) {
            chartLastUpdatedService.cacheLastUpdated(rolledBackState.getLogicalAsOfAt());
        }
    }

    private LocalDateTime resolveLogicalAsOfAt() {
        return releaseRatingSummaryRepository.findMaxBatchSyncedAt()
                .or(() -> releaseRatingSummaryRepository.findMaxUpdatedAt())
                .orElse(LocalDateTime.now());
    }

    private void evictLegacyChartCache() {
        final Set<String> keys = redisTemplate.keys("chart:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
