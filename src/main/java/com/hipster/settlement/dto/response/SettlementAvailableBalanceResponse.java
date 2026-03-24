package com.hipster.settlement.dto.response;

public record SettlementAvailableBalanceResponse(
        Long userId,
        String currency,
        long totalAccruedAmount,
        long availableAmount,
        long reservedAmount,
        boolean payoutEligible
) {
}
