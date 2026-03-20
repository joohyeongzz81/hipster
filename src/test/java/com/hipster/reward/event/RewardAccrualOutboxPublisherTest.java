package com.hipster.reward.event;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.service.RewardAccrualOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RewardAccrualOutboxPublisherTest {

    @InjectMocks
    private RewardAccrualOutboxPublisher rewardAccrualOutboxPublisher;

    @Mock
    private RewardAccrualOutboxService rewardAccrualOutboxService;

    @Mock
    private RewardMetricsRecorder rewardMetricsRecorder;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rewardAccrualOutboxPublisher, "publishConfirmTimeoutMillis", 5_000L);
    }

    @Test
    @DisplayName("ready outbox rows are published to RabbitMQ and marked dispatched")
    void publishReadyOutboxMessages_DispatchesReadyRows() {
        final RewardAccrualOutbox outbox = RewardAccrualOutbox.pending(1001L, "catalog_bootstrap_v1");
        ReflectionTestUtils.setField(outbox, "id", 501L);

        given(rewardAccrualOutboxService.requeueStaleDispatched()).willReturn(0);
        given(rewardAccrualOutboxService.findReadyToDispatch()).willReturn(List.of(outbox));
        given(rewardAccrualOutboxService.tryMarkDispatched(501L)).willReturn(true);
        given(rabbitTemplate.invoke(any())).willReturn(null);

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        verify(rewardAccrualOutboxService).tryMarkDispatched(501L);
        verify(rabbitTemplate).invoke(any());
        verify(rewardMetricsRecorder).recordOutboxPublish("confirmed");
    }

    @Test
    @DisplayName("publish failure marks outbox row failed")
    void publishReadyOutboxMessages_MarksFailedWhenPublishThrows() {
        final RewardAccrualOutbox outbox = RewardAccrualOutbox.pending(1002L, "catalog_bootstrap_v1");
        ReflectionTestUtils.setField(outbox, "id", 502L);

        given(rewardAccrualOutboxService.requeueStaleDispatched()).willReturn(0);
        given(rewardAccrualOutboxService.findReadyToDispatch()).willReturn(List.of(outbox));
        given(rewardAccrualOutboxService.tryMarkDispatched(502L)).willReturn(true);
        doThrow(new IllegalStateException("broker down")).when(rabbitTemplate).invoke(any());

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        verify(rewardAccrualOutboxService).markFailed(502L, "IllegalStateException");
        verify(rewardMetricsRecorder).recordOutboxPublish("publish_failed");
    }

    @Test
    @DisplayName("publisher requeues stale dispatched rows before publishing ready outbox rows")
    void publishReadyOutboxMessages_RequeuesStaleDispatchedRows() {
        given(rewardAccrualOutboxService.requeueStaleDispatched()).willReturn(2);
        given(rewardAccrualOutboxService.findReadyToDispatch()).willReturn(List.of());

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        verify(rewardMetricsRecorder, times(2)).recordOutboxRecover("dispatched_timeout_requeued");
    }

    @Test
    @DisplayName("dispatch claim failure skips duplicate publish attempt")
    void publishReadyOutboxMessages_SkipsWhenDispatchClaimFails() {
        final RewardAccrualOutbox outbox = RewardAccrualOutbox.pending(1003L, "catalog_bootstrap_v1");
        ReflectionTestUtils.setField(outbox, "id", 503L);

        given(rewardAccrualOutboxService.requeueStaleDispatched()).willReturn(0);
        given(rewardAccrualOutboxService.findReadyToDispatch()).willReturn(List.of(outbox));
        given(rewardAccrualOutboxService.tryMarkDispatched(503L)).willReturn(false);

        rewardAccrualOutboxPublisher.publishReadyOutboxMessages();

        verify(rewardMetricsRecorder).recordOutboxPublish("dispatch_claim_skipped");
    }
}
