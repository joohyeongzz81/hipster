package com.hipster.settlement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record SettlementWebhookRequest(
        @NotBlank String providerName,
        @NotBlank String providerEventId,
        String providerReference,
        @NotBlank String eventType,
        @NotNull LocalDateTime eventOccurredAt,
        String payloadHash,
        String payloadBody
) {
}
