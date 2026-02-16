package com.hipster.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "사용자 이름은 필수입니다.")
        @Size(min = 3, max = 50, message = "사용자 이름은 3자 이상 50자 이하로 입력해주세요.")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "사용자 이름은 영문, 숫자, 밑줄(_), 하이픈(-)만 사용할 수 있습니다.")
        String username,

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "유효한 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해주세요.")
        String password
) {
}
