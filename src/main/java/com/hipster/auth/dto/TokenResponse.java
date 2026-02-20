package com.hipster.auth.dto;

import com.hipster.auth.UserRole;
import com.hipster.auth.jwt.JwtProperties;
import com.hipster.auth.jwt.JwtTokenProvider;
import com.hipster.user.domain.User;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Long expiresIn,
        UserProfileDto user
) {
    public TokenResponse(final String accessToken, final String refreshToken, final Long expiresIn,
                         final UserProfileDto user) {
        this(accessToken, refreshToken, "Bearer", expiresIn, user);
    }

    public static TokenResponse of(final User user, final JwtTokenProvider jwtTokenProvider,
                                   final JwtProperties jwtProperties, final String refreshTokenValue) {
        final UserRole role = user.getModerationRole() != null ? user.getModerationRole() : UserRole.USER;
        final String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), role);
        final UserProfileDto userProfile = UserProfileDto.from(user);

        return new TokenResponse(
                accessToken,
                refreshTokenValue,
                jwtProperties.getAccessTokenExpiry() / 1000,
                userProfile
        );
    }
}
