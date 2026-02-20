package com.hipster.rating.domain;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
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
    public Rating(final Long userId, final Long releaseId, final Double score, final Double userWeightingScore) {
        this.userId = userId;
        this.releaseId = releaseId;
        this.updateScore(score, userWeightingScore);
    }

    public void updateScore(final Double score, final Double userWeightingScore) {
        validateScore(score);
        this.score = score;
        this.weightedScore = score * userWeightingScore;
    }

    private void validateScore(final Double score) {
        if (score < 0.5 || score > 5.0 || score % 0.5 != 0) {
            throw new BadRequestException(ErrorCode.INVALID_RATING_SCORE);
        }
    }
}
