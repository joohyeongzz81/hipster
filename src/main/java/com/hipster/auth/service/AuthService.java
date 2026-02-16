package com.hipster.auth.service;

import com.hipster.auth.UserRole;
import com.hipster.auth.domain.RefreshToken;
import com.hipster.auth.dto.LoginRequest;
import com.hipster.auth.dto.RegisterRequest;
import com.hipster.auth.dto.TokenResponse;
import com.hipster.auth.dto.UserProfileDto;
import com.hipster.auth.jwt.JwtProperties;
import com.hipster.auth.jwt.JwtTokenProvider;
import com.hipster.auth.repository.RefreshTokenRepository;
import com.hipster.global.exception.*;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public TokenResponse register(RegisterRequest request) {
        validateEmailDuplication(request.email());
        validateUsernameDuplication(request.username());

        User newUser = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();

        User savedUser = userRepository.save(newUser);

        return createTokenResponse(savedUser, true);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException(ErrorCode.INVALID_PASSWORD);
        }

        user.updateLastActiveDate();

        return createTokenResponse(user, true);
    }

    public TokenResponse refreshToken(String refreshTokenValue) {
        jwtTokenProvider.validateToken(refreshTokenValue);

        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new InvalidTokenException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        return createTokenResponse(refreshToken.getUser(), true);
    }
    
    private void validateEmailDuplication(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    private void validateUsernameDuplication(String username) {
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    private TokenResponse createTokenResponse(User user, boolean issueRefreshToken) {
        UserRole role = user.getModerationRole() != null ? user.getModerationRole() : UserRole.USER;
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), role);
        String refreshToken = null;

        if (issueRefreshToken) {
            refreshToken = manageRefreshToken(user);
        }

        UserProfileDto userProfile = UserProfileDto.from(user);

        return new TokenResponse(
                accessToken,
                refreshToken,
                jwtProperties.getAccessTokenExpiry() / 1000,
                userProfile
        );
    }

    private String manageRefreshToken(User user) {
        String tokenValue = jwtTokenProvider.generateRefreshToken(user.getId());
        Instant expiryDate = Instant.now().plusMillis(jwtProperties.getRefreshTokenExpiry());

        refreshTokenRepository.findByUser(user)
                .ifPresentOrElse(
                        refreshToken -> refreshToken.updateToken(tokenValue, expiryDate),
                        () -> refreshTokenRepository.save(new RefreshToken(user, tokenValue, expiryDate))
                );
        return tokenValue;
    }
}

