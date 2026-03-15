package com.hipster.chart.publish.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "chart_publish_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChartPublishHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chart_name", nullable = false, length = 100)
    private String chartName;

    @Column(nullable = false, length = 64)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChartPublishStatus status;

    @Column(name = "row_count_mysql")
    private Long rowCountMysql;

    @Column(name = "doc_count_es")
    private Long docCountEs;

    @Lob
    @Column(name = "validation_summary_json", columnDefinition = "TEXT")
    private String validationSummaryJson;

    @Column(name = "source_snapshot_at")
    private LocalDateTime sourceSnapshotAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ChartPublishHistory(final String chartName,
                                final String version,
                                final ChartPublishStatus status,
                                final Long rowCountMysql,
                                final Long docCountEs,
                                final String validationSummaryJson,
                                final LocalDateTime sourceSnapshotAt,
                                final LocalDateTime publishedAt,
                                final LocalDateTime rolledBackAt) {
        this.chartName = chartName;
        this.version = version;
        this.status = status;
        this.rowCountMysql = rowCountMysql;
        this.docCountEs = docCountEs;
        this.validationSummaryJson = validationSummaryJson;
        this.sourceSnapshotAt = sourceSnapshotAt;
        this.publishedAt = publishedAt;
        this.rolledBackAt = rolledBackAt;
    }

    public static ChartPublishHistory of(final String chartName,
                                         final String version,
                                         final ChartPublishStatus status,
                                         final Long rowCountMysql,
                                         final Long docCountEs,
                                         final String validationSummaryJson,
                                         final LocalDateTime sourceSnapshotAt,
                                         final LocalDateTime publishedAt,
                                         final LocalDateTime rolledBackAt) {
        return new ChartPublishHistory(
                chartName,
                version,
                status,
                rowCountMysql,
                docCountEs,
                validationSummaryJson,
                sourceSnapshotAt,
                publishedAt,
                rolledBackAt
        );
    }
}
