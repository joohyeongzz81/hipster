package com.hipster.settlement.dto.response;

public record SettlementWebhookReceiveResponse(
        String providerName,
        String providerEventId,
        String processingStatus
) {
}
