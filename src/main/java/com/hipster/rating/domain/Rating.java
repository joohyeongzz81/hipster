package com.hipster.rating.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ratings", indexes = {
        @Index(name = "idx_ratings_user_release", columnList = "userId, releaseId", unique = true),
        @Index(name = "idx_ratings_release_weighted", columnList = "releaseId"),
        @Index(name = "idx_ratings_user_created", columnList = "userId, createdAt DESC")
})
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long releaseId;

    @Column(nullable = false)
    private Double score;

    @Column(nullable = false)
    private Double weightedScore;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Rating(Long userId, Long releaseId, Double score, Double userWeightingScore) {
        this.userId = userId;
        this.releaseId = releaseId;
        this.updateScore(score, userWeightingScore);
    }

    public void updateScore(Double score, Double userWeightingScore) {
        if (score < 0.5 || score > 5.0) {
            throw new IllegalArgumentException("Score must be between 0.5 and 5.0");
        }
        if (score % 0.5 != 0) {
            throw new IllegalArgumentException("Score must be in increments of 0.5");
        }
        this.score = score;
        this.weightedScore = score * userWeightingScore;
    }
}
