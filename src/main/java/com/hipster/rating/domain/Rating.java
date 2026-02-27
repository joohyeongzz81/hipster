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
        @Index(name = "idx_ratings_release", columnList = "releaseId"),
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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Rating(final Long userId, final Long releaseId, final Double score) {
        this.userId = userId;
        this.releaseId = releaseId;
        this.updateScore(score);
    }

    public void updateScore(final Double score) {
        validateScore(score);
        this.score = score;
    }

    private void validateScore(final Double score) {
        if (score < 0.5 || score > 5.0 || score % 0.5 != 0) {
            throw new BadRequestException(ErrorCode.INVALID_RATING_SCORE);
        }
    }
}
