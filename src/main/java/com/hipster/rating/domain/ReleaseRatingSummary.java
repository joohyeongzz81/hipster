package com.hipster.rating.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "release_rating_summary", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"release_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ReleaseRatingSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "total_rating_count", nullable = false)
    private long totalRatingCount = 0L;

    @Column(name = "average_score", nullable = false)
    private double averageScore = 0.0;

    @Column(name = "weighted_score_sum", nullable = false, precision = 19, scale = 4)
    private BigDecimal weightedScoreSum = BigDecimal.ZERO;

    @Column(name = "weighted_count_sum", nullable = false, precision = 19, scale = 4)
    private BigDecimal weightedCountSum = BigDecimal.ZERO;

    @Column(name = "bayesian_score", nullable = false, precision = 19, scale = 10)
    private BigDecimal bayesianScore = BigDecimal.ZERO;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public ReleaseRatingSummary(Long releaseId) {
        this.releaseId = releaseId;
        this.totalRatingCount = 0L;
        this.averageScore = 0.0;
        this.weightedScoreSum = BigDecimal.ZERO;
        this.weightedCountSum = BigDecimal.ZERO;
        this.bayesianScore = BigDecimal.ZERO;
    }

    public void applyDelta(BigDecimal scoreDelta, BigDecimal credibility, long countDelta, BigDecimal m, BigDecimal C) {
        this.weightedScoreSum = this.weightedScoreSum.add(scoreDelta.multiply(credibility));
        this.weightedCountSum = this.weightedCountSum.add(credibility);
        this.bayesianScore = calculateBayesian(m, C);

        double currentTotal = this.averageScore * this.totalRatingCount;
        this.totalRatingCount += countDelta;
        this.averageScore = this.totalRatingCount == 0 ? 0.0 : (currentTotal + scoreDelta.doubleValue()) / this.totalRatingCount;
    }

    public void recalculate(long totalRatingCount, double averageScore, BigDecimal weightedScoreSum, BigDecimal weightedCountSum, BigDecimal m, BigDecimal C) {
        this.totalRatingCount = totalRatingCount;
        this.averageScore = averageScore;
        this.weightedScoreSum = weightedScoreSum;
        this.weightedCountSum = weightedCountSum;
        this.bayesianScore = calculateBayesian(m, C);
    }

    private BigDecimal calculateBayesian(BigDecimal m, BigDecimal C) {
        if (this.weightedCountSum.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal numerator = C.multiply(m).add(this.weightedScoreSum);
        BigDecimal denominator = C.add(this.weightedCountSum);

        return numerator.divide(denominator, 10, RoundingMode.HALF_UP);
    }
}
