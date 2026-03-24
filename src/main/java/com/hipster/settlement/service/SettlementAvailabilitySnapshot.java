package com.hipster.settlement.service;

import com.hipster.reward.domain.RewardLedgerEntry;

import java.util.List;

record SettlementAvailabilitySnapshot(
        Long userId,
        String currency,
        long totalAccruedAmount,
        long allocatableAmount,
        long reservedAmount,
        long openAdjustmentDebitAmount,
        List<RewardLedgerEntry> allocatableEntries
) {

    long availableAmount() {
        return Math.max(0L, allocatableAmount - openAdjustmentDebitAmount);
    }

    boolean payoutEligible(final long minimumPayoutAmount) {
        return !allocatableEntries.isEmpty() && availableAmount() >= minimumPayoutAmount;
    }
}
