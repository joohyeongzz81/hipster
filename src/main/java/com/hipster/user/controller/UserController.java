package com.hipster.user.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.user.dto.UserProfileResponse;
import com.hipster.user.dto.WeightingResponse;
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
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserProfile(id));
    }

    @GetMapping("/{id}/weighting")
    public ResponseEntity<WeightingResponse> getUserWeighting(
            @PathVariable Long id,
            @CurrentUser CurrentUserInfo userInfo) {
        return ResponseEntity.ok(userService.getUserWeighting(id, userInfo.userId()));
    }
}
