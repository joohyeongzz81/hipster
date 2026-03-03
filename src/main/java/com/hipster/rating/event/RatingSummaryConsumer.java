package com.hipster.rating.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.rating.service.RatingSummaryService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RatingSummaryConsumer {

    private final RatingSummaryService ratingSummaryService;

    @RabbitListener(
            id = "ratingSummaryListener",
            queues = RabbitMqConfig.RATING_SUMMARY_QUEUE,
            containerFactory = "rabbitListenerContainerFactory" // 수동 ACK 모드 적용 팩토리
    )
    public void consumeRatingSummaryEvent(
            RatingEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        try {
            log.info("Consumer [RatingSummary]: Processing releaseId={}", event.releaseId());
            ratingSummaryService.applyRatingEvent(event);
            channel.basicAck(deliveryTag, false);
            log.info("Consumer [RatingSummary]: Successfully ACKed deliveryTag={}", deliveryTag);

        } catch (IllegalArgumentException | IllegalStateException | DataIntegrityViolationException e) {
            // [영구 실패] 데이터 자체의 오류 - 재처리해도 동일하게 실패하므로 DLQ로 라우팅 (Poison Pill 방어)
            log.error("Consumer [RatingSummary]: Permanent failure. Discarding message to DLQ. deliveryTag={}, error={}", deliveryTag, e.getMessage());
            channel.basicNack(deliveryTag, false, false);

        } catch (Exception e) {
            // [일시적 장애] DB 커넥션 오류 등 - 재처리 시 회복 가능성이 있으므로 큐에 반환 (requeue)
            log.error("Consumer [RatingSummary]: Transient failure. Requeueing message. deliveryTag={}, error={}", deliveryTag, e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
