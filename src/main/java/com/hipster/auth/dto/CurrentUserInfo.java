package com.hipster.auth.dto;

import com.hipster.auth.UserRole;

public record CurrentUserInfo(
        Long userId,
        UserRole role
) {
}
