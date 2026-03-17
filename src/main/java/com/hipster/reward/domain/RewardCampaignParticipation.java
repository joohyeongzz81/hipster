package com.hipster.reward.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
@Table(name = "reward_campaign_participations", indexes = {
        @Index(name = "idx_reward_participation_campaign_user", columnList = "campaignCode, userId", unique = true)
})
public class RewardCampaignParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String campaignCode;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private RewardCampaignParticipation(final String campaignCode,
                                        final Long userId,
                                        final boolean active) {
        this.campaignCode = campaignCode;
        this.userId = userId;
        this.active = active;
    }

    public static RewardCampaignParticipation activeParticipation(final String campaignCode, final Long userId) {
        return RewardCampaignParticipation.builder()
                .campaignCode(campaignCode)
                .userId(userId)
                .active(true)
                .build();
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
