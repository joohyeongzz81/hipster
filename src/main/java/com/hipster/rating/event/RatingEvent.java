package com.hipster.rating.event;

public record RatingEvent(
        Long userId, // MQ Fan-out 후 User Activity Consumer 가 사용하도록 식별자 추가
        Long releaseId,
        double oldScore,
        double newScore,
        boolean isCreated
) {
}
