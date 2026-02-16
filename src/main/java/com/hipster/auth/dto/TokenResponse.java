package com.hipster.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Long expiresIn,
        UserProfileDto user
) {
    public TokenResponse(String accessToken, String refreshToken, Long expiresIn, UserProfileDto user) {
        this(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
