package com.hipster.chart.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chart_scores", indexes = {
        @Index(name = "idx_chart_release", columnList = "releaseId", unique = true),
        @Index(name = "idx_chart_bayesian", columnList = "bayesianScore DESC")
})
public class ChartScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long releaseId;

    @Column(nullable = false)
    private Long totalRatings;

    @Column(nullable = false)
    private Double effectiveVotes;

    @Column(nullable = false)
    private Double weightedAvgRating;

    @Column(nullable = false)
    private Double bayesianScore;

    @Column(nullable = false)
    private Boolean isEsoteric;

    @Column(nullable = false)
    private LocalDateTime lastUpdated;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public ChartScore(final Long releaseId) {
        this.releaseId = releaseId;
    }

    public void updateScore(final Double bayesianScore, final Double weightedAvgRating,
                            final Double effectiveVotes, final Long totalRatings,
                            final Boolean isEsoteric) {
        this.bayesianScore = bayesianScore;
        this.weightedAvgRating = weightedAvgRating;
        this.effectiveVotes = effectiveVotes;
        this.totalRatings = totalRatings;
        this.isEsoteric = isEsoteric;
        this.lastUpdated = LocalDateTime.now();
    }
}
