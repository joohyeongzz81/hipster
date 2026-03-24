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
@Table(name = "settlement_adjustments", indexes = {
        @Index(name = "idx_settlement_adjustment_request_status", columnList = "settlement_request_id, status"),
        @Index(name = "idx_settlement_adjustment_user_status", columnList = "user_id, status")
})
public class SettlementAdjustment {

    private static final int MAX_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_request_id", nullable = false, updatable = false)
    private Long settlementRequestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 30)
    private SettlementAdjustmentType adjustmentType;

    @Column(name = "amount_delta", nullable = false)
    private long amountDelta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementAdjustmentStatus status;

    @Column(length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "resolved_by_settlement_request_id")
    private Long resolvedBySettlementRequestId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private SettlementAdjustment(final Long settlementRequestId,
                                 final Long userId,
                                 final SettlementAdjustmentType adjustmentType,
                                 final long amountDelta,
                                 final String reason) {
        this.settlementRequestId = Objects.requireNonNull(settlementRequestId);
        this.userId = Objects.requireNonNull(userId);
        this.adjustmentType = Objects.requireNonNull(adjustmentType);
        this.amountDelta = amountDelta;
        this.reason = trimReason(reason);
        this.status = SettlementAdjustmentStatus.OPEN;
    }

    public static SettlementAdjustment open(final Long settlementRequestId,
                                            final Long userId,
                                            final SettlementAdjustmentType adjustmentType,
                                            final long amountDelta,
                                            final String reason) {
        return new SettlementAdjustment(settlementRequestId, userId, adjustmentType, amountDelta, reason);
    }

    public void resolveBy(final Long settlementRequestId) {
        this.status = SettlementAdjustmentStatus.RESOLVED;
        this.resolvedBySettlementRequestId = settlementRequestId;
    }

    private String trimReason(final String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH);
    }
}
