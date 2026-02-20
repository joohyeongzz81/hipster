package com.hipster.user.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.user.dto.response.UserProfileResponse;
import com.hipster.user.dto.response.WeightingResponse;
import com.hipster.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
