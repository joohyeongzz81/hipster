package com.hipster.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(
        @NotBlank(message = "본인 확인을 위해 비밀번호를 입력해주세요.")
        String password
) {
}
