package com.hipster.reward.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
@Table(name = "reward_campaigns")
public class RewardCampaign {

    @Id
    @Column(nullable = false, length = 100)
    private String code;

    @Version
    private Long version;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private long pointsPerApproval;

    @Column(nullable = false)
    private long totalPointCap;

    @Column(nullable = false)
    private long grantedPoints;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private RewardCampaign(final String code,
                           final String name,
                           final boolean active,
                           final long pointsPerApproval,
                           final long totalPointCap,
                           final long grantedPoints) {
        this.code = code;
        this.name = name;
        this.active = active;
        this.pointsPerApproval = pointsPerApproval;
        this.totalPointCap = totalPointCap;
        this.grantedPoints = grantedPoints;
    }

    public static RewardCampaign defaultCampaign(final String code,
                                                 final String name,
                                                 final long pointsPerApproval,
                                                 final long totalPointCap) {
        return RewardCampaign.builder()
                .code(code)
                .name(name)
                .active(true)
                .pointsPerApproval(pointsPerApproval)
                .totalPointCap(totalPointCap)
                .grantedPoints(0L)
                .build();
    }

    public boolean canAccrue(final long points) {
        return active && grantedPoints + points <= totalPointCap;
    }

    public void accrue(final long points) {
        this.grantedPoints += points;
    }

    public void reverse(final long points) {
        this.grantedPoints = Math.max(0L, this.grantedPoints - points);
    }
}
