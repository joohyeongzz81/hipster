package com.hipster.chart.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
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
}
