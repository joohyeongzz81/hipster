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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reward_ledger_entries", indexes = {
        @Index(name = "idx_reward_approval_campaign_type", columnList = "approvalId, campaignCode, entryType", unique = true),
        @Index(name = "idx_reward_user_campaign", columnList = "userId, campaignCode")
})
public class RewardLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long approvalId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String campaignCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardLedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RewardLedgerEntryStatus entryStatus;

    @Column(nullable = false)
    private long pointsDelta;

    @Column(nullable = true)
    private Long referenceEntryId;

    @Column(nullable = true, length = 150)
    private String reason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private RewardLedgerEntry(final Long approvalId,
                              final Long userId,
                              final String campaignCode,
                              final RewardLedgerEntryType entryType,
                              final RewardLedgerEntryStatus entryStatus,
                              final long pointsDelta,
                              final Long referenceEntryId,
                              final String reason) {
        this.approvalId = approvalId;
        this.userId = userId;
        this.campaignCode = campaignCode;
        this.entryType = entryType;
        this.entryStatus = entryStatus;
        this.pointsDelta = pointsDelta;
        this.referenceEntryId = referenceEntryId;
        this.reason = reason;
    }

    public static RewardLedgerEntry accrued(final Long approvalId,
                                            final Long userId,
                                            final String campaignCode,
                                            final long pointsDelta) {
        return RewardLedgerEntry.builder()
                .approvalId(approvalId)
                .userId(userId)
                .campaignCode(campaignCode)
                .entryType(RewardLedgerEntryType.ACCRUAL)
                .entryStatus(RewardLedgerEntryStatus.ACCRUED)
                .pointsDelta(pointsDelta)
                .build();
    }

    public static RewardLedgerEntry blocked(final Long approvalId,
                                            final Long userId,
                                            final String campaignCode,
                                            final RewardLedgerEntryStatus entryStatus,
                                            final String reason) {
        return RewardLedgerEntry.builder()
                .approvalId(approvalId)
                .userId(userId)
                .campaignCode(campaignCode)
                .entryType(RewardLedgerEntryType.ACCRUAL)
                .entryStatus(entryStatus)
                .pointsDelta(0L)
                .reason(reason)
                .build();
    }

    public static RewardLedgerEntry reversal(final Long approvalId,
                                             final Long userId,
                                             final String campaignCode,
                                             final long pointsDelta,
                                             final Long referenceEntryId,
                                             final String reason) {
        return RewardLedgerEntry.builder()
                .approvalId(approvalId)
                .userId(userId)
                .campaignCode(campaignCode)
                .entryType(RewardLedgerEntryType.REVERSAL)
                .entryStatus(RewardLedgerEntryStatus.REVERSED)
                .pointsDelta(-Math.abs(pointsDelta))
                .referenceEntryId(referenceEntryId)
                .reason(reason)
                .build();
    }
}
