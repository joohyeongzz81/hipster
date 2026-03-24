package com.hipster.settlement.controller;

import com.hipster.global.dto.response.ApiResponse;
import com.hipster.settlement.dto.request.SettlementWebhookRequest;
import com.hipster.settlement.dto.response.SettlementWebhookReceiveResponse;
import com.hipster.settlement.service.SettlementWebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlements/webhooks")
@RequiredArgsConstructor
public class SettlementWebhookController {

    private final SettlementWebhookService settlementWebhookService;

    @PostMapping("/mock-payout")
    public ResponseEntity<ApiResponse<SettlementWebhookReceiveResponse>> receiveMockPayoutWebhook(
            @RequestBody @Valid final SettlementWebhookRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(settlementWebhookService.acceptWebhook(request)));
    }
}
