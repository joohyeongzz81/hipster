package com.hipster.user.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.user.dto.request.ChangePasswordRequest;
import com.hipster.user.dto.request.DeleteAccountRequest;
import com.hipster.user.dto.request.UpdateProfileRequest;
import com.hipster.user.dto.response.UserProfileResponse;
import com.hipster.user.dto.response.WeightingResponse;
import com.hipster.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserProfile(@PathVariable final Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserProfile(id)));
    }

    @GetMapping("/{id}/weighting")
    public ResponseEntity<ApiResponse<WeightingResponse>> getUserWeighting(
            @PathVariable final Long id,
            @CurrentUser final CurrentUserInfo userInfo) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserWeighting(id, userInfo.userId())));
    }

    @PutMapping("/me/profile")
    public ResponseEntity<ApiResponse<Void>> updateProfile(
            @CurrentUser final CurrentUserInfo userInfo,
            @RequestBody @Valid final UpdateProfileRequest request) {
        userService.updateProfile(userInfo.userId(), request);
        return ResponseEntity.ok(ApiResponse.of(200, "회원 정보가 수정되었습니다.", null));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @CurrentUser final CurrentUserInfo userInfo,
            @RequestBody @Valid final ChangePasswordRequest request) {
        userService.changePassword(userInfo.userId(), request);
        return ResponseEntity.ok(ApiResponse.of(200, "비밀번호가 변경되었습니다.", null));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(
            @CurrentUser final CurrentUserInfo userInfo,
            @RequestBody @Valid final DeleteAccountRequest request) {
        userService.deleteAccount(userInfo.userId(), request);
        return ResponseEntity.ok(ApiResponse.of(200, "회원 탈퇴가 완료되었습니다.", null));
    }
}
