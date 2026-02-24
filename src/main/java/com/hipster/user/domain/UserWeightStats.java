package com.hipster.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_weight_stats")
public class UserWeightStats {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private Long ratingCount;

    @Column(nullable = false)
    private Double ratingVariance;

    @Column(nullable = false)
    private Long reviewCount;

    @Column(nullable = false)
    private Double reviewAvgLength;

    @Column
    private LocalDateTime lastActiveDate;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime lastCalculatedAt;

    @Builder
    public UserWeightStats(final Long userId, final Long ratingCount, final Double ratingVariance,
                           final Long reviewCount, final Double reviewAvgLength, final LocalDateTime lastActiveDate) {
        this.userId = userId;
        this.ratingCount = ratingCount;
        this.ratingVariance = ratingVariance;
        this.reviewCount = reviewCount;
        this.reviewAvgLength = reviewAvgLength;
        this.lastActiveDate = lastActiveDate;
    }

    public void update(final Long ratingCount, final Double ratingVariance,
                       final Long reviewCount, final Double reviewAvgLength, final LocalDateTime lastActiveDate) {
        this.ratingCount = ratingCount;
        this.ratingVariance = ratingVariance;
        this.reviewCount = reviewCount;
        this.reviewAvgLength = reviewAvgLength;
        this.lastActiveDate = lastActiveDate;
    }
}
