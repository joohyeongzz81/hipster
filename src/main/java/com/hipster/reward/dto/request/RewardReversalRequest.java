package com.hipster.reward.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RewardReversalRequest(
        @NotBlank(message = "취소 적립 사유는 필수입니다.")
        String reason
) {
}
