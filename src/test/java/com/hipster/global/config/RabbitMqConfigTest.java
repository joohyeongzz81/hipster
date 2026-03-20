package com.hipster.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RabbitMqConfigTest {

    private final RabbitMqConfig rabbitMqConfig = new RabbitMqConfig();

    @Test
    void ratingSummaryQueue_ConfiguresDeadLetterRouting() {
        Queue queue = rabbitMqConfig.ratingSummaryQueue();

        assertEquals(RabbitMqConfig.RATING_SUMMARY_DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitMqConfig.RATING_SUMMARY_DLQ_ROUTING_KEY, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void ratingSummaryDeadLetterQueue_UsesDedicatedQueueName() {
        Queue deadLetterQueue = rabbitMqConfig.ratingSummaryDeadLetterQueue();

        assertEquals(RabbitMqConfig.RATING_SUMMARY_DLQ, deadLetterQueue.getName());
    }
}
