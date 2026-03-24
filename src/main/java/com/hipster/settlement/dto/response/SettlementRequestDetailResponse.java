package com.hipster.settlement.dto.response;

import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;

import java.time.LocalDateTime;

public record SettlementRequestDetailResponse(
        String requestNo,
        Long userId,
        SettlementRequestStatus status,
        String currency,
        long requestedAmount,
        long reservedAmount,
        boolean minimumPayoutSatisfied,
        String destinationSnapshot,
        String providerName,
        String providerReference,
        LocalDateTime requestedAt,
        LocalDateTime sentAt,
        LocalDateTime finalizedAt
) {
    public static SettlementRequestDetailResponse from(final SettlementRequest request) {
        return new SettlementRequestDetailResponse(
                request.getRequestNo(),
                request.getUserId(),
                request.getStatus(),
                request.getCurrency(),
                request.getRequestedAmount(),
                request.getReservedAmount(),
                request.isMinimumPayoutSatisfied(),
                request.getDestinationSnapshot(),
                request.getProviderName(),
                request.getProviderReference(),
                request.getRequestedAt(),
                request.getSentAt(),
                request.getFinalizedAt()
        );
    }
}
