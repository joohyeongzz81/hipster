package com.hipster.auth.dto;

public record CurrentUserInfo(
        Long userId,
        String role
) {
}
