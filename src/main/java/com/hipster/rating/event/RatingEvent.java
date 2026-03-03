package com.hipster.rating.event;

import java.time.LocalDateTime;

public record RatingEvent(
        Long userId, // MQ Fan-out 후 User Activity Consumer 가 사용하도록 식별자 추가
        Long releaseId,
        double oldScore,
        double newScore,
        boolean isCreated,
        boolean isDeleted,
        double weightingScore,
        LocalDateTime eventTs // 이벤트를 발행한 트랜잭션의 실행 시각 (Race Condition 파악용)
) {
}
