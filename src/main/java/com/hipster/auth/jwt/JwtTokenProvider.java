package com.hipster.auth.jwt;

import com.hipster.auth.UserRole;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final JwtProperties jwtProperties;

    public JwtTokenProvider(final JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(final Long userId, final UserRole role) {
        final Date now = new Date();
        final Date expiryDate = new Date(now.getTime() + jwtProperties.getAccessTokenExpiry());

        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(final Long userId) {
        final Date now = new Date();
        final Date expiryDate = new Date(now.getTime() + jwtProperties.getRefreshTokenExpiry());

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public void validateToken(final String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * Refresh Token 재발급 전용 서명 검증 메서드.
     * 만료된 토큰이더라도 서명이 유효하면 통과시키고,
     * 만료 여부는 DB expiryDate 기준으로 판단합니다.
     */
    public void validateRefreshTokenSignature(final String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        } catch (ExpiredJwtException ignored) {
            // 만료는 DB expiryDate 기준으로 판단하므로 무시
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(ErrorCode.INVALID_TOKEN);
        }
    }

    public Long extractUserId(final String token) {
        return Long.parseLong(extractAllClaims(token).getSubject());
    }

    public String extractRole(final String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public Date getExpiration(final String token) {
        return extractAllClaims(token).getExpiration();
    }

    private Claims extractAllClaims(final String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(ErrorCode.INVALID_TOKEN);
        }
    }
}
