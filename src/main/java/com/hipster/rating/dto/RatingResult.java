package com.hipster.rating.dto;

public record RatingResult(
        RatingResponse response,
        boolean isCreated
) {
}
