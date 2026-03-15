package com.hipster.chart.publish.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishHistory;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.publish.domain.ChartPublishStatus;
import com.hipster.chart.publish.domain.ChartValidationStatus;
import com.hipster.chart.publish.repository.ChartPublishHistoryRepository;
import com.hipster.chart.publish.repository.ChartPublishStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChartPublishStateService {

    private static final DateTimeFormatter VERSION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String PUBLISHED_PROJECTION_REF = "chart_scores";

    private final ChartPublishProperties chartPublishProperties;
    private final ChartPublishStateRepository chartPublishStateRepository;
    private final ChartPublishHistoryRepository chartPublishHistoryRepository;
    private final ObjectMapper objectMapper;

    public String generateNextVersion() {
        return "v" + LocalDateTime.now().format(VERSION_FORMAT);
    }

    @Transactional
    public CandidateVersionContext startCandidateGeneration(final String candidateVersion,
                                                            final LocalDateTime logicalAsOfAt,
                                                            final String candidateMysqlProjectionRef,
                                                            final String candidateEsIndexRef) {
        final ChartPublishState state = getOrCreateState();

        final String previousVersion = state.getCurrentVersion();
        final String previousMysqlProjectionRef = state.getMysqlProjectionRef();
        final String previousEsIndexRef = state.getEsIndexRef();
        final LocalDateTime previousLogicalAsOfAt = state.getLogicalAsOfAt();

        state.beginGeneration(candidateVersion, candidateMysqlProjectionRef, candidateEsIndexRef, logicalAsOfAt);
        chartPublishStateRepository.save(state);
        appendHistory(candidateVersion, ChartPublishStatus.GENERATING, null, null, null, logicalAsOfAt, null, null);

        return new CandidateVersionContext(
                chartPublishProperties.getChartName(),
                candidateVersion,
                previousVersion,
                previousMysqlProjectionRef,
                previousEsIndexRef,
                previousLogicalAsOfAt,
                candidateMysqlProjectionRef,
                candidateEsIndexRef,
                logicalAsOfAt
        );
    }

    @Transactional(readOnly = true)
    public ChartPublishState requireState() {
        return chartPublishStateRepository.findById(chartPublishProperties.getChartName())
                .orElseThrow(() -> new IllegalStateException("chart_publish_state is not initialized."));
    }

    @Transactional(readOnly = true)
    public Optional<String> getCurrentVersion() {
        return chartPublishStateRepository.findById(chartPublishProperties.getChartName())
                .map(ChartPublishState::getCurrentVersion);
    }

    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getCurrentLogicalAsOfAt() {
        return chartPublishStateRepository.findById(chartPublishProperties.getChartName())
                .map(ChartPublishState::getLogicalAsOfAt);
    }

    @Transactional
    public void markValidating(final String version) {
        final ChartPublishState state = requireState();
        state.markValidating(version);
        chartPublishStateRepository.save(state);
    }

    @Transactional
    public ValidationGateResult recordValidationResult(final String version,
                                                       final long mysqlRowCount,
                                                       final long esDocCount,
                                                       final boolean searchable,
                                                       final boolean blockingPassed,
                                                       final String validationSummaryJson,
                                                       final LocalDateTime sourceSnapshotAt) {
        final ChartPublishState state = requireState();
        state.markValidationStatus(version, blockingPassed ? ChartValidationStatus.PASSED : ChartValidationStatus.FAILED);
        chartPublishStateRepository.save(state);

        appendHistory(
                version,
                ChartPublishStatus.VALIDATING,
                mysqlRowCount,
                esDocCount,
                validationSummaryJson,
                sourceSnapshotAt,
                null,
                null
        );

        return new ValidationGateResult(version, mysqlRowCount, esDocCount, searchable, blockingPassed, validationSummaryJson);
    }

    @Transactional
    public ChartPublishState markPublishing(final String version) {
        final ChartPublishState state = requireState();
        state.markPublishing(version);
        return chartPublishStateRepository.save(state);
    }

    @Transactional
    public ChartPublishState bootstrapPublishedState(final String version,
                                                     final String mysqlProjectionRef,
                                                     final String esIndexRef,
                                                     final LocalDateTime logicalAsOfAt,
                                                     final LocalDateTime publishedAt) {
        final ChartPublishState state = getOrCreateState();
        state.bootstrapPublished(version, mysqlProjectionRef, esIndexRef, logicalAsOfAt, publishedAt);
        final ChartPublishState saved = chartPublishStateRepository.save(state);
        appendHistory(
                version,
                ChartPublishStatus.PUBLISHED,
                null,
                null,
                jsonOf(Map.of("bootstrap", true, "publishedVersion", version)),
                logicalAsOfAt,
                publishedAt,
                null
        );
        return saved;
    }

    @Transactional
    public ChartPublishState markPublished(final String version) {
        final ChartPublishState state = requireState();
        final LocalDateTime publishedAt = LocalDateTime.now();
        state.publishCandidate(version, PUBLISHED_PROJECTION_REF, publishedAt);
        final ChartPublishState saved = chartPublishStateRepository.save(state);
        appendHistory(
                version,
                ChartPublishStatus.PUBLISHED,
                null,
                null,
                jsonOf(Map.of("publishedVersion", version)),
                saved.getLogicalAsOfAt(),
                publishedAt,
                null
        );
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChartPublishState markFailed(final String errorCode, final String errorMessage) {
        final ChartPublishState state = requireState();
        final String failedVersion = state.getCandidateVersion() != null ? state.getCandidateVersion() : state.getCurrentVersion();
        state.markFailed(errorCode, errorMessage);
        final ChartPublishState saved = chartPublishStateRepository.save(state);
        final Map<String, Object> errorSummary = new LinkedHashMap<>();
        errorSummary.put("errorCode", errorCode);
        errorSummary.put("errorMessage", errorMessage);
        appendHistory(
                failedVersion,
                ChartPublishStatus.FAILED,
                null,
                null,
                jsonOf(errorSummary),
                state.getCandidateLogicalAsOfAt(),
                null,
                null
        );
        return saved;
    }

    @Transactional
    public ChartPublishState markRolledBack(final String rolledBackVersion, final String reason) {
        final ChartPublishState state = requireState();
        state.rollbackToPrevious(LocalDateTime.now());
        final ChartPublishState saved = chartPublishStateRepository.save(state);
        final Map<String, Object> rollbackSummary = new LinkedHashMap<>();
        rollbackSummary.put("reason", reason);
        rollbackSummary.put("restoredVersion", saved.getCurrentVersion());
        appendHistory(
                rolledBackVersion,
                ChartPublishStatus.ROLLED_BACK,
                null,
                null,
                jsonOf(rollbackSummary),
                saved.getLogicalAsOfAt(),
                null,
                LocalDateTime.now()
        );
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<ChartPublishHistory> findHistoryByVersion(final String version) {
        return chartPublishHistoryRepository.findTopByChartNameAndVersionOrderByCreatedAtDesc(chartPublishProperties.getChartName(), version);
    }

    public String validationSummaryJson(final long mysqlRowCount,
                                        final long esDocCount,
                                        final boolean searchable,
                                        final boolean blockingPassed) {
        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mysqlRowCount", mysqlRowCount);
        summary.put("esDocCount", esDocCount);
        summary.put("searchable", searchable);
        summary.put("blockingPassed", blockingPassed);
        return jsonOf(summary);
    }

    private ChartPublishState getOrCreateState() {
        return chartPublishStateRepository.findById(chartPublishProperties.getChartName())
                .orElseGet(() -> chartPublishStateRepository.save(ChartPublishState.initialize(chartPublishProperties.getChartName())));
    }

    private void appendHistory(final String version,
                               final ChartPublishStatus status,
                               final Long rowCountMysql,
                               final Long docCountEs,
                               final String validationSummaryJson,
                               final LocalDateTime sourceSnapshotAt,
                               final LocalDateTime publishedAt,
                               final LocalDateTime rolledBackAt) {
        if (version == null) {
            return;
        }
        chartPublishHistoryRepository.save(ChartPublishHistory.of(
                chartPublishProperties.getChartName(),
                version,
                status,
                rowCountMysql,
                docCountEs,
                validationSummaryJson,
                sourceSnapshotAt,
                publishedAt,
                rolledBackAt
        ));
    }

    private String jsonOf(final Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"serializationError\":\"" + e.getMessage() + "\"}";
        }
    }

    public record CandidateVersionContext(
            String chartName,
            String version,
            String previousVersion,
            String previousMysqlProjectionRef,
            String previousEsIndexRef,
            LocalDateTime previousLogicalAsOfAt,
            String candidateMysqlProjectionRef,
            String candidateEsIndexRef,
            LocalDateTime logicalAsOfAt
    ) {
    }

    public record ValidationGateResult(
            String version,
            long mysqlRowCount,
            long esDocCount,
            boolean searchable,
            boolean blockingPassed,
            String summaryJson
    ) {
    }
}
