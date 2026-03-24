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
import jakarta.persistence.Version;
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
@Table(name = "settlement_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_request_request_no", columnNames = "request_no"),
        @UniqueConstraint(name = "uk_settlement_request_open_user", columnNames = "open_request_user_id")
}, indexes = {
        @Index(name = "idx_settlement_request_user_status", columnList = "user_id, status"),
        @Index(name = "idx_settlement_request_provider_reference", columnList = "provider_reference")
})
public class SettlementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_no", nullable = false, length = 64, updatable = false)
    private String requestNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SettlementRequestStatus status;

    @Column(nullable = false, length = 20)
    private String currency;

    @Column(name = "requested_amount", nullable = false)
    private long requestedAmount;

    @Column(name = "reserved_amount", nullable = false)
    private long reservedAmount;

    @Column(name = "minimum_payout_satisfied", nullable = false)
    private boolean minimumPayoutSatisfied;

    @Column(name = "destination_snapshot", nullable = false, length = 1000)
    private String destinationSnapshot;

    @Column(name = "provider_name", length = 50)
    private String providerName;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "open_request_user_id")
    private Long openRequestUserId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private SettlementRequest(final String requestNo,
                              final Long userId,
                              final String currency,
                              final long requestedAmount,
                              final long reservedAmount,
                              final boolean minimumPayoutSatisfied,
                              final String destinationSnapshot) {
        this.requestNo = Objects.requireNonNull(requestNo);
        this.userId = Objects.requireNonNull(userId);
        this.currency = Objects.requireNonNull(currency);
        this.requestedAmount = requestedAmount;
        this.reservedAmount = reservedAmount;
        this.minimumPayoutSatisfied = minimumPayoutSatisfied;
        this.destinationSnapshot = Objects.requireNonNull(destinationSnapshot);
        this.status = SettlementRequestStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
        this.openRequestUserId = userId;
    }

    public static SettlementRequest requested(final String requestNo,
                                              final Long userId,
                                              final String currency,
                                              final long requestedAmount,
                                              final long reservedAmount,
                                              final boolean minimumPayoutSatisfied,
                                              final String destinationSnapshot) {
        return new SettlementRequest(
                requestNo,
                userId,
                currency,
                requestedAmount,
                reservedAmount,
                minimumPayoutSatisfied,
                destinationSnapshot
        );
    }

    public void markReserved() {
        requireStatus(SettlementRequestStatus.REQUESTED);
        this.status = SettlementRequestStatus.RESERVED;
    }

    public void markSent(final String providerName, final String providerReference) {
        requireAnyStatus(SettlementRequestStatus.RESERVED);
        this.status = SettlementRequestStatus.SENT;
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.sentAt = LocalDateTime.now();
    }

    public void markUnknown(final String providerName, final String providerReference) {
        requireAnyStatus(SettlementRequestStatus.RESERVED, SettlementRequestStatus.SENT);
        this.status = SettlementRequestStatus.UNKNOWN;
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.sentAt = this.sentAt == null ? LocalDateTime.now() : this.sentAt;
    }

    public void markSucceeded(final String providerName, final String providerReference) {
        requireAnyStatus(SettlementRequestStatus.SENT, SettlementRequestStatus.UNKNOWN);
        this.status = SettlementRequestStatus.SUCCEEDED;
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.sentAt = this.sentAt == null ? LocalDateTime.now() : this.sentAt;
        this.finalizedAt = LocalDateTime.now();
        clearOpenRequestMarker();
    }

    public void markFailed(final String providerName, final String providerReference) {
        requireAnyStatus(SettlementRequestStatus.RESERVED, SettlementRequestStatus.SENT, SettlementRequestStatus.UNKNOWN);
        this.status = SettlementRequestStatus.FAILED;
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.finalizedAt = LocalDateTime.now();
        clearOpenRequestMarker();
    }

    public void markNeedsAdjustment() {
        requireStatus(SettlementRequestStatus.SUCCEEDED);
        this.status = SettlementRequestStatus.NEEDS_ADJUSTMENT;
        this.finalizedAt = this.finalizedAt == null ? LocalDateTime.now() : this.finalizedAt;
        clearOpenRequestMarker();
    }

    public boolean isOpen() {
        return status.isOpen();
    }

    private void requireStatus(final SettlementRequestStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("SettlementRequest status must be " + expected + " but was " + this.status);
        }
    }

    private void requireAnyStatus(final SettlementRequestStatus... allowedStatuses) {
        for (SettlementRequestStatus allowedStatus : allowedStatuses) {
            if (this.status == allowedStatus) {
                return;
            }
        }
        throw new IllegalStateException("SettlementRequest status transition is not allowed from " + this.status);
    }

    private void clearOpenRequestMarker() {
        this.openRequestUserId = null;
    }
}
