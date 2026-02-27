package com.hipster.rating.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
    private long totalRatingCount;

    @Column(name = "average_score", nullable = false)
    private double averageScore;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public ReleaseRatingSummary(Long releaseId, long totalRatingCount, double averageScore) {
        this.releaseId = releaseId;
        this.totalRatingCount = totalRatingCount;
        this.averageScore = averageScore;
    }

    public void updateStats(long totalRatingCount, double averageScore) {
        this.totalRatingCount = totalRatingCount;
        this.averageScore = averageScore;
    }
}
