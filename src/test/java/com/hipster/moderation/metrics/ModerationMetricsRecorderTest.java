package com.hipster.moderation.metrics;

import com.hipster.moderation.domain.ModerationAuditEventType;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.repository.ModerationQueueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModerationMetricsRecorderTest {

    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 동기화가 켜져 있으면 moderation action counter는 커밋 후 증가한다")
    void recordAction_IncrementsAfterCommitWhenSynchronizationIsActive() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ModerationMetricsRecorder recorder = new ModerationMetricsRecorder(meterRegistry, moderationQueueRepository, 24L);

        TransactionSynchronizationManager.initSynchronization();

        recorder.recordAction(ModerationAuditEventType.CLAIMED);

        double beforeCommit = meterRegistry.get("moderation.queue.actions")
                .tag("event_type", "claimed")
                .counter()
                .count();
        assertThat(beforeCommit).isZero();

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        double afterCommit = meterRegistry.get("moderation.queue.actions")
                .tag("event_type", "claimed")
                .counter()
                .count();
        assertThat(afterCommit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("moderation backlog gauge는 현재 pending, under review, SLA 초과 건 수를 노출한다")
    void gauges_ExposeCurrentBacklogCounts() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        given(moderationQueueRepository.countByStatus(ModerationStatus.PENDING)).willReturn(3L);
        given(moderationQueueRepository.countByStatus(ModerationStatus.UNDER_REVIEW)).willReturn(2L);
        given(moderationQueueRepository.countByStatusInAndSubmittedAtLessThanEqual(anyCollection(), any(LocalDateTime.class)))
                .willReturn(1L);

        ModerationMetricsRecorder recorder = new ModerationMetricsRecorder(meterRegistry, moderationQueueRepository, 24L);

        assertThat(meterRegistry.get("moderation.queue.backlog").tag("bucket", "pending").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get("moderation.queue.backlog").tag("bucket", "under_review").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("moderation.queue.backlog").tag("bucket", "sla_breached").gauge().value()).isEqualTo(1.0);

        verify(moderationQueueRepository).countByStatus(ModerationStatus.PENDING);
        verify(moderationQueueRepository).countByStatus(ModerationStatus.UNDER_REVIEW);
        verify(moderationQueueRepository).countByStatusInAndSubmittedAtLessThanEqual(anyCollection(), any(LocalDateTime.class));
        assertThat(recorder).isNotNull();
    }
}
