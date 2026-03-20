package com.hipster.reward.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.service.RewardAccrualOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hipster.reward.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class RewardAccrualOutboxPublisher {

    private final RewardAccrualOutboxService rewardAccrualOutboxService;
    private final RewardMetricsRecorder rewardMetricsRecorder;
    private final RabbitTemplate rabbitTemplate;

    @org.springframework.beans.factory.annotation.Value("${hipster.reward.outbox.publish-confirm-timeout-ms:5000}")
    private long publishConfirmTimeoutMillis;

    @Scheduled(fixedDelayString = "${hipster.reward.outbox.publish-fixed-delay-ms:5000}")
    public void publishReadyOutboxMessages() {
        final int recoveredCount = rewardAccrualOutboxService.requeueStaleDispatched();
        for (int i = 0; i < recoveredCount; i++) {
            rewardMetricsRecorder.recordOutboxRecover("dispatched_timeout_requeued");
        }

        for (final RewardAccrualOutbox outbox : rewardAccrualOutboxService.findReadyToDispatch()) {
            if (!rewardAccrualOutboxService.tryMarkDispatched(outbox.getId())) {
                rewardMetricsRecorder.recordOutboxPublish("dispatch_claim_skipped");
                continue;
            }

            try {
                rabbitTemplate.invoke(operations -> {
                    operations.convertAndSend(
                            RabbitMqConfig.REWARD_ACCRUAL_EXCHANGE,
                            RabbitMqConfig.REWARD_ACCRUAL_ROUTING_KEY,
                            new RewardAccrualMessage(outbox.getId(), outbox.getApprovalId(), outbox.getCampaignCode())
                    );
                    operations.waitForConfirmsOrDie(publishConfirmTimeoutMillis);
                    return null;
                });
                rewardMetricsRecorder.recordOutboxPublish("confirmed");
            } catch (RuntimeException exception) {
                rewardAccrualOutboxService.markFailed(outbox.getId(), exception.getClass().getSimpleName());
                rewardMetricsRecorder.recordOutboxPublish("publish_failed");
                log.error("Reward accrual outbox publish failed. outboxId={}, approvalId={}",
                        outbox.getId(), outbox.getApprovalId(), exception);
            }
        }
    }
}
