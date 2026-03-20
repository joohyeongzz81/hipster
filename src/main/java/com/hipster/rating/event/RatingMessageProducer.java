package com.hipster.rating.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.rating.metrics.RatingMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RatingMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final RatingMetricsRecorder ratingMetricsRecorder;

    /**
     * RatingService에서 메인 트랜잭션이 안전하게 DB에 커밋된 직후에만 호출됩니다. (무결성 방어)
     * RabbitMQ Fanout Exchange로 이벤트를 발행(Publish)합니다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void forwardRatingEventToRabbitMq(RatingEvent event) {
        try {
            log.info("Producer [RatingMessageProducer]: Publishing releaseId={}, userId={}, created={}, deleted={}",
                    event.releaseId(), event.userId(), event.isCreated(), event.isDeleted());
            rabbitTemplate.convertAndSend(RabbitMqConfig.RATING_EVENT_EXCHANGE, "", event);
            ratingMetricsRecorder.recordPublish("published");
        } catch (RuntimeException e) {
            ratingMetricsRecorder.recordPublish("publish_failed");
            log.error("Producer [RatingMessageProducer]: Publish failed. releaseId={}, userId={}, created={}, deleted={}",
                    event.releaseId(), event.userId(), event.isCreated(), event.isDeleted(), e);
            throw e;
        }
    }
}
