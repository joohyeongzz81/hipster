package com.hipster.auth.service;

import com.hipster.auth.domain.RefreshToken;
import com.hipster.auth.dto.request.LoginRequest;
import com.hipster.auth.dto.request.RegisterRequest;
import com.hipster.auth.dto.response.TokenResponse;
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
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    @Transactional
    public TokenResponse register(final RegisterRequest request) {
        validateEmailDuplication(request.email());
        validateUsernameDuplication(request.username());

        final User newUser = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();

        final User savedUser = userRepository.save(newUser);
        final String refreshTokenValue = manageRefreshToken(savedUser);

        return TokenResponse.of(savedUser, jwtTokenProvider, jwtProperties, refreshTokenValue);
    }

    @Transactional
    public TokenResponse login(final LoginRequest request) {
        final User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        validatePassword(request.password(), user.getPasswordHash());
        user.updateLastActiveDate();

        final String refreshTokenValue = manageRefreshToken(user);

        return TokenResponse.of(user, jwtTokenProvider, jwtProperties, refreshTokenValue);
    }

    @Transactional
    public TokenResponse refreshToken(final String refreshTokenValue) {
        jwtTokenProvider.validateToken(refreshTokenValue);

        final RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        validateRefreshTokenExpiry(refreshToken);

        final String newRefreshTokenValue = manageRefreshToken(refreshToken.getUser());

        return TokenResponse.of(refreshToken.getUser(), jwtTokenProvider, jwtProperties, newRefreshTokenValue);
    }

    private void validateEmailDuplication(final String email) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    private void validateUsernameDuplication(final String username) {
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    private void validatePassword(final String rawPassword, final String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new UnauthorizedException(ErrorCode.INVALID_PASSWORD);
        }
    }

    private void validateRefreshTokenExpiry(final RefreshToken refreshToken) {
        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new InvalidTokenException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
    }

    private String manageRefreshToken(final User user) {
        final String tokenValue = jwtTokenProvider.generateRefreshToken(user.getId());
        final Instant expiryDate = Instant.now().plusMillis(jwtProperties.getRefreshTokenExpiry());

        refreshTokenRepository.findByUser(user)
                .ifPresentOrElse(
                        existing -> existing.updateToken(tokenValue, expiryDate),
                        () -> refreshTokenRepository.save(new RefreshToken(user, tokenValue, expiryDate))
                );
        return tokenValue;
    }
}
