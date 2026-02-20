package com.hipster.auth.dto.response;

import com.hipster.auth.UserRole;

public record CurrentUserInfo(
        Long userId,
        UserRole role
) {
}
