package com.hipster.review.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateReviewPublicationRequest(
        @NotNull(message = "공개 여부는 필수입니다.")
        Boolean isPublished
) {
}
