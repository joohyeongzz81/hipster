package com.hipster.auth.controller;

import com.hipster.auth.dto.LoginRequest;
import com.hipster.auth.dto.RefreshTokenRequest;
import com.hipster.auth.dto.RegisterRequest;
import com.hipster.auth.dto.TokenResponse;
import com.hipster.auth.service.AuthService;
import com.hipster.global.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TokenResponse>> register(@Valid @RequestBody final RegisterRequest request) {
        final TokenResponse tokenResponse = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(HttpStatus.CREATED.value(), "회원가입이 완료되었습니다.", tokenResponse));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody final LoginRequest request) {
        final TokenResponse tokenResponse = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok(tokenResponse));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refreshToken(@Valid @RequestBody final RefreshTokenRequest request) {
        final TokenResponse tokenResponse = authService.refreshToken(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(tokenResponse));
    }
}
