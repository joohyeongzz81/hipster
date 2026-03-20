package com.hipster.reward.event;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.service.RewardAccrualOutboxService;
import com.hipster.reward.service.RewardLedgerService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RewardAccrualRabbitConsumerTest {

    @InjectMocks
    private RewardAccrualRabbitConsumer rewardAccrualRabbitConsumer;

    @Mock
    private RewardLedgerService rewardLedgerService;

    @Mock
    private RewardAccrualOutboxService rewardAccrualOutboxService;

    @Mock
    private RewardMetricsRecorder rewardMetricsRecorder;

    @Mock
    private Channel channel;

    @Test
    @DisplayName("consumer processes accrual and marks outbox row processed")
    void consumeRewardAccrualMessage_ProcessesSuccessfully() throws Exception {
        final RewardAccrualMessage message = new RewardAccrualMessage(901L, 10001L, "catalog_bootstrap_v1");

        rewardAccrualRabbitConsumer.consumeRewardAccrualMessage(message, channel, 77L);

        verify(rewardLedgerService).accrueApprovedContribution(10001L);
        verify(rewardAccrualOutboxService).markProcessed(901L);
        verify(rewardMetricsRecorder).recordOutboxConsume("processed");
        verify(rewardMetricsRecorder).recordAsyncAccrualProcessing("success");
        verify(channel).basicAck(77L, false);
    }

    @Test
    @DisplayName("consumer failure marks outbox row failed and still acks the message")
    void consumeRewardAccrualMessage_MarksFailedOnProcessingError() throws Exception {
        final RewardAccrualMessage message = new RewardAccrualMessage(902L, 10002L, "catalog_bootstrap_v1");
        doThrow(new BadRequestException(ErrorCode.REWARD_APPROVAL_NOT_ELIGIBLE))
                .when(rewardLedgerService).accrueApprovedContribution(10002L);

        rewardAccrualRabbitConsumer.consumeRewardAccrualMessage(message, channel, 78L);

        verify(rewardAccrualOutboxService).markFailed(902L, "reward_approval_not_eligible");
        verify(rewardMetricsRecorder).recordOutboxConsume("processing_failed");
        verify(rewardMetricsRecorder).recordAsyncAccrualProcessing("reward_approval_not_eligible");
        verify(channel).basicAck(78L, false);
    }

    @Test
    @DisplayName("outbox status update failure nacks the message for retry")
    void consumeRewardAccrualMessage_NacksWhenFailureStatusUpdateAlsoFails() throws Exception {
        final RewardAccrualMessage message = new RewardAccrualMessage(903L, 10003L, "catalog_bootstrap_v1");
        doThrow(new IllegalStateException("boom"))
                .when(rewardLedgerService).accrueApprovedContribution(10003L);
        doThrow(new IllegalStateException("cannot update outbox"))
                .when(rewardAccrualOutboxService).markFailed(903L, "illegalstateexception");

        rewardAccrualRabbitConsumer.consumeRewardAccrualMessage(message, channel, 79L);

        verify(channel).basicNack(79L, false, true);
    }
}
