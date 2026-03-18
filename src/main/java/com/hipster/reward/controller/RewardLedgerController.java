package com.hipster.reward.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.auth.UserRole;
import com.hipster.auth.annotation.RequireRole;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.reward.dto.request.RewardReversalRequest;
import com.hipster.reward.dto.response.RewardApprovalAccrualResponse;
import com.hipster.reward.dto.response.UserRewardApprovalAccrualListResponse;
import com.hipster.reward.dto.response.UserRewardBalanceResponse;
import com.hipster.reward.service.RewardLedgerService;
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
@RequestMapping("/api/v1/reward-ledger")
@RequiredArgsConstructor
public class RewardLedgerController {

    private final RewardLedgerService rewardLedgerService;

    @GetMapping("/me/approvals")
    public ResponseEntity<ApiResponse<UserRewardApprovalAccrualListResponse>> getMyApprovalAccruals(
            @CurrentUser final CurrentUserInfo currentUser) {
        return ResponseEntity.ok(ApiResponse.ok(rewardLedgerService.getUserApprovalAccruals(currentUser.userId())));
    }

    @GetMapping("/me/balance")
    public ResponseEntity<ApiResponse<UserRewardBalanceResponse>> getMyRewardBalance(
            @CurrentUser final CurrentUserInfo currentUser) {
        return ResponseEntity.ok(ApiResponse.ok(rewardLedgerService.getUserRewardBalance(currentUser.userId())));
    }

    @GetMapping("/approvals/{approvalId}")
    @RequireRole(UserRole.ADMIN)
    public ResponseEntity<ApiResponse<RewardApprovalAccrualResponse>> getApprovalAccrual(
            @PathVariable final Long approvalId) {
        return ResponseEntity.ok(ApiResponse.ok(rewardLedgerService.getApprovalAccrual(approvalId)));
    }

    @PostMapping("/approvals/{approvalId}/reversal")
    @RequireRole(UserRole.ADMIN)
    public ResponseEntity<ApiResponse<RewardApprovalAccrualResponse>> reverseApprovalAccrual(
            @PathVariable final Long approvalId,
            @RequestBody @Valid final RewardReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(rewardLedgerService.reverseApprovalAccrual(approvalId, request.reason())));
    }

    @GetMapping("/users/{userId}/balance")
    @RequireRole(UserRole.ADMIN)
    public ResponseEntity<ApiResponse<UserRewardBalanceResponse>> getUserRewardBalance(
            @PathVariable final Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(rewardLedgerService.getUserRewardBalance(userId)));
    }
}
