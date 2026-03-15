package com.hipster.chart.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chart_scores")
public class ChartScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id", nullable = false, unique = true)
    private Release release;

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

    @Column(name = "genre_ids", columnDefinition = "JSON")
    private String genreIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_type")
    private ReleaseType releaseType;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Column(name = "descriptor_ids", columnDefinition = "JSON")
    private String descriptorIds;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "languages", columnDefinition = "JSON")
    private String languages;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public ChartScore(final Release release) {
        this.release = release;
    }

    public Long getReleaseId() {
        return release != null ? release.getId() : null;
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
