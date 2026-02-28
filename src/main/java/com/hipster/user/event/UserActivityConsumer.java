package com.hipster.user.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.rating.event.RatingEvent;
import com.hipster.user.repository.UserRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityConsumer {

    private final UserRepository userRepository;

    @RabbitListener(
            queues = RabbitMqConfig.USER_ACTIVITY_QUEUE,
            containerFactory = "rabbitListenerContainerFactory" // 수동 ACK 적용
    )
    public void consumeUserActivityEvent(
            RatingEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        try {
            log.info("Consumer [UserActivity]: Processing userId={}", event.userId());
            userRepository.updateLastActiveDate(event.userId(), LocalDateTime.now());
            
            // 정상 처리 후 수동 ACK (메시지 증발 방지용)
            channel.basicAck(deliveryTag, false);
            log.info("Consumer [UserActivity]: Successfully ACKed deliveryTag={}", deliveryTag);
        } catch (Exception e) {
            // 다른 큐나 전체 시스템에 영향을 주지 않는 완벽한 장애 격리(Fault Isolation)
            log.error("Consumer [UserActivity]: Exception occurred. NACK and Requeue! Error: {}", e.getMessage());
            // requeue = true 을 통해 일시적 장애인 경우 나중에 재처리되도록 큐에 반환
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
