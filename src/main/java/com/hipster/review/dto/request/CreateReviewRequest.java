package com.hipster.review.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReviewRequest(
        @NotBlank(message = "리뷰 내용은 필수입니다.")
        @Size(min = 10, max = 10000, message = "리뷰는 10자 이상 10000자 이하여야 합니다.")
        String content
) {
}
