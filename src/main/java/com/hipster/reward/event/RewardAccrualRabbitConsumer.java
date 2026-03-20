package com.hipster.reward.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.global.exception.BusinessException;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.service.RewardAccrualOutboxService;
import com.hipster.reward.service.RewardLedgerService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class RewardAccrualRabbitConsumer {

    private final RewardLedgerService rewardLedgerService;
    private final RewardAccrualOutboxService rewardAccrualOutboxService;
    private final RewardMetricsRecorder rewardMetricsRecorder;

    @RabbitListener(
            id = "rewardAccrualListener",
            queues = RabbitMqConfig.REWARD_ACCRUAL_QUEUE,
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeRewardAccrualMessage(final RewardAccrualMessage message,
                                            final Channel channel,
                                            @Header(AmqpHeaders.DELIVERY_TAG) final long deliveryTag) throws IOException {
        try {
            rewardLedgerService.accrueApprovedContribution(message.approvalId());
            rewardAccrualOutboxService.markProcessed(message.outboxId());
            rewardMetricsRecorder.recordOutboxConsume("processed");
            rewardMetricsRecorder.recordAsyncAccrualProcessing("success");
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            final String outcome = resolveOutcome(exception);

            try {
                rewardAccrualOutboxService.markFailed(message.outboxId(), outcome);
            } catch (RuntimeException markFailure) {
                log.error("Reward accrual outbox status update failed. outboxId={}, approvalId={}",
                        message.outboxId(), message.approvalId(), markFailure);
                channel.basicNack(deliveryTag, false, true);
                return;
            }

            rewardMetricsRecorder.recordOutboxConsume("processing_failed");
            rewardMetricsRecorder.recordAsyncAccrualProcessing(outcome);
            log.error("Reward accrual processing failed. outboxId={}, approvalId={}, outcome={}",
                    message.outboxId(), message.approvalId(), outcome, exception);
            channel.basicAck(deliveryTag, false);
        }
    }

    private String resolveOutcome(final RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().name().toLowerCase(Locale.ROOT);
        }

        return exception.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}
