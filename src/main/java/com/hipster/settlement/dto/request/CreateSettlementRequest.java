package com.hipster.settlement.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateSettlementRequest(
        @NotBlank String currency,
        @NotBlank String destinationSnapshot
) {
}
