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

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_allocations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_allocation_request_reward_entry", columnNames = {
                "settlement_request_id", "reward_ledger_entry_id"
        }),
        @UniqueConstraint(name = "uk_settlement_allocation_active_reward_entry", columnNames = "active_reward_ledger_entry_id")
}, indexes = {
        @Index(name = "idx_settlement_allocation_request", columnList = "settlement_request_id"),
        @Index(name = "idx_settlement_allocation_reward_entry", columnList = "reward_ledger_entry_id"),
        @Index(name = "idx_settlement_allocation_user_active", columnList = "user_id, active")
})
public class SettlementAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_request_id", nullable = false, updatable = false)
    private Long settlementRequestId;

    @Column(name = "reward_ledger_entry_id", nullable = false, updatable = false)
    private Long rewardLedgerEntryId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "allocated_amount", nullable = false)
    private long allocatedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", nullable = false, length = 32)
    private SettlementAllocationType allocationType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "active_reward_ledger_entry_id")
    private Long activeRewardLedgerEntryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private SettlementAllocation(final Long settlementRequestId,
                                 final Long rewardLedgerEntryId,
                                 final Long userId,
                                 final long allocatedAmount,
                                 final SettlementAllocationType allocationType) {
        this.settlementRequestId = Objects.requireNonNull(settlementRequestId);
        this.rewardLedgerEntryId = Objects.requireNonNull(rewardLedgerEntryId);
        this.userId = Objects.requireNonNull(userId);
        this.allocatedAmount = allocatedAmount;
        this.allocationType = Objects.requireNonNull(allocationType);
        this.active = true;
        this.activeRewardLedgerEntryId = rewardLedgerEntryId;
    }

    public static SettlementAllocation reserve(final Long settlementRequestId,
                                               final Long rewardLedgerEntryId,
                                               final Long userId,
                                               final long allocatedAmount) {
        return new SettlementAllocation(
                settlementRequestId,
                rewardLedgerEntryId,
                userId,
                allocatedAmount,
                SettlementAllocationType.RESERVATION
        );
    }

    public void release() {
        this.active = false;
        this.allocationType = SettlementAllocationType.RELEASE;
        this.activeRewardLedgerEntryId = null;
    }
}
