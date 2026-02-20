package com.hipster.rating.dto.response;

public record RatingResult(
        RatingResponse response,
        boolean isCreated
) {
}
