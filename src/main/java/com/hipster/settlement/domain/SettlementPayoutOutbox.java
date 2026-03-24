package com.hipster.settlement.domain;

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
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_payout_outbox", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_payout_outbox_request", columnNames = "settlement_request_id"),
        @UniqueConstraint(name = "uk_settlement_payout_outbox_provider_key", columnNames = "provider_idempotency_key")
}, indexes = {
        @Index(name = "idx_settlement_payout_outbox_status_next_attempt", columnList = "status, next_attempt_at")
})
public class SettlementPayoutOutbox {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_request_id", nullable = false, updatable = false)
    private Long settlementRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementPayoutOutboxStatus status;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "provider_idempotency_key", nullable = false, length = 100)
    private String providerIdempotencyKey;

    @Column(name = "dispatch_attempt_count", nullable = false)
    private int dispatchAttemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

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

    private SettlementPayoutOutbox(final Long settlementRequestId,
                                   final String providerName,
                                   final String providerIdempotencyKey) {
        this.settlementRequestId = Objects.requireNonNull(settlementRequestId);
        this.providerName = Objects.requireNonNull(providerName);
        this.providerIdempotencyKey = Objects.requireNonNull(providerIdempotencyKey);
        this.status = SettlementPayoutOutboxStatus.PENDING;
        this.nextAttemptAt = LocalDateTime.now();
        this.dispatchAttemptCount = 0;
    }

    public static SettlementPayoutOutbox pending(final Long settlementRequestId,
                                                 final String providerName,
                                                 final String providerIdempotencyKey) {
        return new SettlementPayoutOutbox(settlementRequestId, providerName, providerIdempotencyKey);
    }

    public void markDispatched() {
        if (this.status == SettlementPayoutOutboxStatus.PROCESSED) {
            return;
        }
        this.status = SettlementPayoutOutboxStatus.DISPATCHED;
        this.dispatchedAt = LocalDateTime.now();
        this.nextAttemptAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void markProcessed() {
        this.status = SettlementPayoutOutboxStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
        this.nextAttemptAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void markFailed(final String errorMessage, final LocalDateTime nextAttemptAt) {
        this.status = SettlementPayoutOutboxStatus.FAILED;
        this.dispatchAttemptCount += 1;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = trimError(errorMessage);
    }

    public void markReadyForRetry(final String errorMessage, final LocalDateTime nextAttemptAt) {
        this.status = SettlementPayoutOutboxStatus.FAILED;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = trimError(errorMessage);
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
