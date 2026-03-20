package com.hipster.reward.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reward_accrual_outbox", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reward_accrual_outbox_approval_campaign", columnNames = {"approval_id", "campaign_code"})
}, indexes = {
        @Index(name = "idx_reward_accrual_outbox_status_next_attempt", columnList = "status, next_attempt_at"),
        @Index(name = "idx_reward_accrual_outbox_approval", columnList = "approval_id")
})
public class RewardAccrualOutbox {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "campaign_code", nullable = false, length = 100)
    private String campaignCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardAccrualOutboxStatus status;

    @Column(name = "publish_attempt_count", nullable = false)
    private int publishAttemptCount;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private RewardAccrualOutbox(final Long approvalId,
                                final String campaignCode,
                                final RewardAccrualOutboxStatus status,
                                final LocalDateTime nextAttemptAt) {
        this.approvalId = approvalId;
        this.campaignCode = campaignCode;
        this.status = status;
        this.nextAttemptAt = nextAttemptAt;
        this.publishAttemptCount = 0;
    }

    public static RewardAccrualOutbox pending(final Long approvalId, final String campaignCode) {
        return new RewardAccrualOutbox(approvalId, campaignCode, RewardAccrualOutboxStatus.PENDING, LocalDateTime.now());
    }

    public void markDispatched() {
        if (status == RewardAccrualOutboxStatus.PROCESSED) {
            return;
        }

        status = RewardAccrualOutboxStatus.DISPATCHED;
        dispatchedAt = LocalDateTime.now();
        nextAttemptAt = LocalDateTime.now();
        lastError = null;
    }

    public void markProcessed() {
        status = RewardAccrualOutboxStatus.PROCESSED;
        processedAt = LocalDateTime.now();
        nextAttemptAt = LocalDateTime.now();
        lastError = null;
    }

    public void markFailed(final String errorMessage, final LocalDateTime nextAttemptAt) {
        status = RewardAccrualOutboxStatus.FAILED;
        publishAttemptCount += 1;
        this.nextAttemptAt = nextAttemptAt;
        lastError = trimError(errorMessage);
    }

    public void markReadyForRetry(final String errorMessage, final LocalDateTime nextAttemptAt) {
        status = RewardAccrualOutboxStatus.FAILED;
        this.nextAttemptAt = nextAttemptAt;
        lastError = trimError(errorMessage);
    }

    private String trimError(final String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }
}
