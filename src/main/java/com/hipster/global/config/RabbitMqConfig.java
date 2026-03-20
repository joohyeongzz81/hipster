package com.hipster.global.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

@EnableRabbit
@Configuration
public class RabbitMqConfig {

    public static final String RATING_EVENT_EXCHANGE = "rating.event.exchange";
    public static final String RATING_SUMMARY_QUEUE = "rating.summary.queue";
    public static final String RATING_SUMMARY_DLX = "rating.summary.dlx";
    public static final String RATING_SUMMARY_DLQ = "rating.summary.dlq";
    public static final String RATING_SUMMARY_DLQ_ROUTING_KEY = "rating.summary.dead";
    public static final String USER_ACTIVITY_QUEUE = "user.activity.queue";
    public static final String REWARD_ACCRUAL_EXCHANGE = "reward.accrual.exchange";
    public static final String REWARD_ACCRUAL_QUEUE = "reward.accrual.queue";
    public static final String REWARD_ACCRUAL_ROUTING_KEY = "reward.accrual";

    // 1. Exchange 등록 (Fanout 방식 - 모든 큐에 메시지 전달)
    @Bean
    public FanoutExchange ratingEventExchange() {
        return new FanoutExchange(RATING_EVENT_EXCHANGE);
    }

    // 2. Queue 등록
    @Bean
    public Queue ratingSummaryQueue() {
        return QueueBuilder.durable(RATING_SUMMARY_QUEUE)
                .withArgument("x-dead-letter-exchange", RATING_SUMMARY_DLX)
                .withArgument("x-dead-letter-routing-key", RATING_SUMMARY_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange ratingSummaryDeadLetterExchange() {
        return new DirectExchange(RATING_SUMMARY_DLX, true, false);
    }

    @Bean
    public Queue ratingSummaryDeadLetterQueue() {
        return QueueBuilder.durable(RATING_SUMMARY_DLQ).build();
    }

    @Bean
    public Queue userActivityQueue() {
        return new Queue(USER_ACTIVITY_QUEUE, true);
    }

    @Bean
    public DirectExchange rewardAccrualExchange() {
        return new DirectExchange(REWARD_ACCRUAL_EXCHANGE, true, false);
    }

    @Bean
    public Queue rewardAccrualQueue() {
        return new Queue(REWARD_ACCRUAL_QUEUE, true);
    }

    // 3. Binding 적용 (Exchange -> Queue)
    @Bean
    public Binding ratingSummaryBinding(@Qualifier("ratingSummaryQueue") Queue ratingSummaryQueue,
                                        @Qualifier("ratingEventExchange") FanoutExchange ratingEventExchange) {
        return BindingBuilder.bind(ratingSummaryQueue).to(ratingEventExchange);
    }

    @Bean
    public Binding ratingSummaryDeadLetterBinding(@Qualifier("ratingSummaryDeadLetterQueue") Queue ratingSummaryDeadLetterQueue,
                                                  @Qualifier("ratingSummaryDeadLetterExchange") DirectExchange ratingSummaryDeadLetterExchange) {
        return BindingBuilder.bind(ratingSummaryDeadLetterQueue)
                .to(ratingSummaryDeadLetterExchange)
                .with(RATING_SUMMARY_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding userActivityBinding(@Qualifier("userActivityQueue") Queue userActivityQueue,
                                       @Qualifier("ratingEventExchange") FanoutExchange ratingEventExchange) {
        return BindingBuilder.bind(userActivityQueue).to(ratingEventExchange);
    }

    @Bean
    public Binding rewardAccrualBinding(@Qualifier("rewardAccrualQueue") final Queue rewardAccrualQueue,
                                        @Qualifier("rewardAccrualExchange") final DirectExchange rewardAccrualExchange) {
        return BindingBuilder.bind(rewardAccrualQueue)
                .to(rewardAccrualExchange)
                .with(REWARD_ACCRUAL_ROUTING_KEY);
    }

    // 4. Message Converter (JSON 직렬화)
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // 5. RabbitTemplate 설정
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        return rabbitTemplate;
    }

    // 6. Listener Container Factory (수동 ACK 및 로직 격리 설정)
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        // 장애 격리와 데이터 유실 방지를 위한 수동 ACK 모드 적용
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        return factory;
    }

    // 7. RabbitAdmin (큐 및 메시지 상태 관리 및 테스트 검증용)
    @Bean
    public org.springframework.amqp.rabbit.core.RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new org.springframework.amqp.rabbit.core.RabbitAdmin(connectionFactory);
    }
}
