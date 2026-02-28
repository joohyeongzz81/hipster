package com.hipster.rating.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RatingSummaryConsumer {

    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;

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
            if (event.isCreated()) {
                releaseRatingSummaryRepository.incrementRating(event.releaseId(), event.newScore());
            } else if (event.oldScore() != event.newScore()) {
                releaseRatingSummaryRepository.updateRatingScore(event.releaseId(), event.oldScore(), event.newScore());
            }
            // 정상 처리 완료 시 RabbitMQ 브로커에 메시지 삭제 요청 (ACK)
            channel.basicAck(deliveryTag, false);
            log.info("Consumer [RatingSummary]: Successfully ACKed deliveryTag={}", deliveryTag);
        } catch (Exception e) {
            log.error("Consumer [RatingSummary]: Exception occurred. NACK and Requeue! Error: {}", e.getMessage());
            // 예외 발생 시 NACK 전송 -> requeue=true로 설정하여 브로커가 메시지를 버리지 않고 다시 큐에 보관 (장애 대응 및 영속성)
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
