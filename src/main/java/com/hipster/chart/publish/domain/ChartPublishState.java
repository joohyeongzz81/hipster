package com.hipster.chart.publish.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "chart_publish_state")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChartPublishState {

    @Id
    @Column(name = "chart_name", nullable = false, updatable = false, length = 100)
    private String chartName;

    @Column(name = "current_version", length = 64)
    private String currentVersion;

    @Column(name = "previous_version", length = 64)
    private String previousVersion;

    @Column(name = "candidate_version", length = 64)
    private String candidateVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChartPublishStatus status;

    @Column(name = "mysql_projection_ref", length = 128)
    private String mysqlProjectionRef;

    @Column(name = "previous_mysql_projection_ref", length = 128)
    private String previousMysqlProjectionRef;

    @Column(name = "candidate_mysql_projection_ref", length = 128)
    private String candidateMysqlProjectionRef;

    @Column(name = "es_index_ref", length = 128)
    private String esIndexRef;

    @Column(name = "previous_es_index_ref", length = 128)
    private String previousEsIndexRef;

    @Column(name = "candidate_es_index_ref", length = 128)
    private String candidateEsIndexRef;

    @Column(name = "logical_as_of_at")
    private LocalDateTime logicalAsOfAt;

    @Column(name = "previous_logical_as_of_at")
    private LocalDateTime previousLogicalAsOfAt;

    @Column(name = "candidate_logical_as_of_at")
    private LocalDateTime candidateLogicalAsOfAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_validation_status", nullable = false, length = 32)
    private ChartValidationStatus lastValidationStatus;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private ChartPublishState(final String chartName) {
        this.chartName = chartName;
        this.status = ChartPublishStatus.IDLE;
        this.lastValidationStatus = ChartValidationStatus.NONE;
    }

    public static ChartPublishState initialize(final String chartName) {
        return new ChartPublishState(chartName);
    }

    public void beginGeneration(final String version,
                                final String candidateMysqlProjectionRef,
                                final String candidateEsIndexRef,
                                final LocalDateTime candidateLogicalAsOfAt) {
        this.candidateVersion = version;
        this.candidateMysqlProjectionRef = candidateMysqlProjectionRef;
        this.candidateEsIndexRef = candidateEsIndexRef;
        this.candidateLogicalAsOfAt = candidateLogicalAsOfAt;
        this.status = ChartPublishStatus.GENERATING;
        this.lastValidationStatus = ChartValidationStatus.NONE;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    public void markValidating(final String version) {
        assertCandidate(version);
        this.status = ChartPublishStatus.VALIDATING;
    }

    public void markValidationStatus(final String version, final ChartValidationStatus validationStatus) {
        assertCandidate(version);
        this.lastValidationStatus = validationStatus;
    }

    public void markPublishing(final String version) {
        assertCandidate(version);
        this.status = ChartPublishStatus.PUBLISHING;
    }

    public void bootstrapPublished(final String version,
                                   final String mysqlProjectionRef,
                                   final String esIndexRef,
                                   final LocalDateTime logicalAsOfAt,
                                   final LocalDateTime publishedAt) {
        this.currentVersion = version;
        this.previousVersion = null;
        this.candidateVersion = null;
        this.mysqlProjectionRef = mysqlProjectionRef;
        this.previousMysqlProjectionRef = null;
        this.candidateMysqlProjectionRef = null;
        this.esIndexRef = esIndexRef;
        this.previousEsIndexRef = null;
        this.candidateEsIndexRef = null;
        this.logicalAsOfAt = logicalAsOfAt;
        this.previousLogicalAsOfAt = null;
        this.candidateLogicalAsOfAt = null;
        this.publishedAt = publishedAt;
        this.status = ChartPublishStatus.PUBLISHED;
        this.lastValidationStatus = ChartValidationStatus.PASSED;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    public void publishCandidate(final String version,
                                 final String publishedMysqlProjectionRef,
                                 final LocalDateTime publishedAt) {
        assertCandidate(version);
        this.previousVersion = this.currentVersion;
        this.previousMysqlProjectionRef = this.mysqlProjectionRef;
        this.previousEsIndexRef = this.esIndexRef;
        this.previousLogicalAsOfAt = this.logicalAsOfAt;

        this.currentVersion = this.candidateVersion;
        this.mysqlProjectionRef = publishedMysqlProjectionRef;
        this.esIndexRef = this.candidateEsIndexRef;
        this.logicalAsOfAt = this.candidateLogicalAsOfAt;
        this.publishedAt = publishedAt;
        this.status = ChartPublishStatus.PUBLISHED;
        this.lastValidationStatus = ChartValidationStatus.PASSED;

        clearCandidate();
    }

    public void markFailed(final String errorCode, final String errorMessage) {
        this.status = ChartPublishStatus.FAILED;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        if (this.lastValidationStatus == ChartValidationStatus.NONE) {
            this.lastValidationStatus = ChartValidationStatus.FAILED;
        }
    }

    public void rollbackToPrevious(final LocalDateTime rollbackAt) {
        this.currentVersion = this.previousVersion;
        this.mysqlProjectionRef = this.previousMysqlProjectionRef;
        this.esIndexRef = this.previousEsIndexRef;
        this.logicalAsOfAt = this.previousLogicalAsOfAt;
        this.publishedAt = rollbackAt;
        this.status = ChartPublishStatus.ROLLED_BACK;
        this.lastValidationStatus = ChartValidationStatus.WARNING;
        clearCandidate();
    }

    private void clearCandidate() {
        this.candidateVersion = null;
        this.candidateMysqlProjectionRef = null;
        this.candidateEsIndexRef = null;
        this.candidateLogicalAsOfAt = null;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    private void assertCandidate(final String version) {
        if (candidateVersion == null || !candidateVersion.equals(version)) {
            throw new IllegalStateException("Candidate version mismatch. expected=" + candidateVersion + ", actual=" + version);
        }
    }
}
