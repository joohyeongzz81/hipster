package com.hipster.settlement.dto.response;

import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;

import java.time.LocalDateTime;

public record SettlementRequestSummaryResponse(
        String requestNo,
        Long userId,
        SettlementRequestStatus status,
        String currency,
        long requestedAmount,
        long reservedAmount,
        LocalDateTime requestedAt
) {
    public static SettlementRequestSummaryResponse from(final SettlementRequest request) {
        return new SettlementRequestSummaryResponse(
                request.getRequestNo(),
                request.getUserId(),
                request.getStatus(),
                request.getCurrency(),
                request.getRequestedAmount(),
                request.getReservedAmount(),
                request.getRequestedAt()
        );
    }
}
