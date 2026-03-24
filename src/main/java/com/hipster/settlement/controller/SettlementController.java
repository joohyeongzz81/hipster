package com.hipster.settlement.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.settlement.dto.request.CreateSettlementRequest;
import com.hipster.settlement.dto.response.SettlementAvailableBalanceResponse;
import com.hipster.settlement.dto.response.SettlementRequestDetailResponse;
import com.hipster.settlement.service.SettlementAvailableBalanceService;
import com.hipster.settlement.service.SettlementRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementAvailableBalanceService settlementAvailableBalanceService;
    private final SettlementRequestService settlementRequestService;

    @GetMapping("/me/available-balance")
    public ResponseEntity<ApiResponse<SettlementAvailableBalanceResponse>> getMyAvailableBalance(
            @CurrentUser final CurrentUserInfo currentUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                settlementAvailableBalanceService.getAvailableBalance(currentUser.userId())
        ));
    }

    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<SettlementRequestDetailResponse>> createRequest(
            @CurrentUser final CurrentUserInfo currentUser,
            @RequestBody @Valid final CreateSettlementRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                settlementRequestService.createRequest(currentUser.userId(), request)
        ));
    }

    @GetMapping("/requests/{requestNo}")
    public ResponseEntity<ApiResponse<SettlementRequestDetailResponse>> getRequest(
            @CurrentUser final CurrentUserInfo currentUser,
            @PathVariable final String requestNo) {
        return ResponseEntity.ok(ApiResponse.ok(
                settlementRequestService.getRequest(currentUser.userId(), requestNo)
        ));
    }
}
