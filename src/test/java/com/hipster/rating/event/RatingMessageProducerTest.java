package com.hipster.rating.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.rating.metrics.RatingMetricsRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RatingMessageProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RatingMetricsRecorder ratingMetricsRecorder;

    @InjectMocks
    private RatingMessageProducer ratingMessageProducer;

    @Test
    void forwardRatingEventToRabbitMq_PublishSuccess_RecordsMetric() {
        RatingEvent event = new RatingEvent(1L, 2L, 0.0, 4.5, true, false, 1.2, LocalDateTime.now());

        ratingMessageProducer.forwardRatingEventToRabbitMq(event);

        verify(rabbitTemplate).convertAndSend(RabbitMqConfig.RATING_EVENT_EXCHANGE, "", event);
        verify(ratingMetricsRecorder).recordPublish("published");
    }

    @Test
    void forwardRatingEventToRabbitMq_PublishFailure_RecordsFailureMetricAndRethrows() {
        RatingEvent event = new RatingEvent(1L, 2L, 0.0, 4.5, true, false, 1.2, LocalDateTime.now());
        RuntimeException failure = new RuntimeException("publish failed");
        doThrow(failure).when(rabbitTemplate).convertAndSend(RabbitMqConfig.RATING_EVENT_EXCHANGE, "", event);

        assertThrows(RuntimeException.class, () -> ratingMessageProducer.forwardRatingEventToRabbitMq(event));

        verify(ratingMetricsRecorder).recordPublish("publish_failed");
    }
}
