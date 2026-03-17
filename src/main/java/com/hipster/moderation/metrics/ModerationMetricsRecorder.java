package com.hipster.moderation.metrics;

import com.hipster.moderation.domain.ModerationAuditEventType;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.repository.ModerationQueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ModerationMetricsRecorder {

    private static final List<ModerationStatus> OPEN_STATUSES = List.of(
            ModerationStatus.PENDING,
            ModerationStatus.UNDER_REVIEW
    );

    private final ModerationQueueRepository moderationQueueRepository;
    private final long moderationSlaHours;
    private final Map<ModerationAuditEventType, Counter> actionCounters = new EnumMap<>(ModerationAuditEventType.class);

    public ModerationMetricsRecorder(final MeterRegistry meterRegistry,
                                     final ModerationQueueRepository moderationQueueRepository,
                                     @Value("${hipster.moderation.sla-hours:24}") final long moderationSlaHours) {
        this.moderationQueueRepository = moderationQueueRepository;
        this.moderationSlaHours = moderationSlaHours;

        for (final ModerationAuditEventType eventType : ModerationAuditEventType.values()) {
            actionCounters.put(eventType, Counter.builder("moderation.queue.actions")
                    .description("Moderation queue action count")
                    .tag("event_type", eventType.name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry));
        }

        Gauge.builder("moderation.queue.backlog", this, ModerationMetricsRecorder::countPendingItems)
                .description("Current moderation backlog grouped by bucket")
                .tag("bucket", "pending")
                .register(meterRegistry);

        Gauge.builder("moderation.queue.backlog", this, ModerationMetricsRecorder::countUnderReviewItems)
                .description("Current moderation backlog grouped by bucket")
                .tag("bucket", "under_review")
                .register(meterRegistry);

        Gauge.builder("moderation.queue.backlog", this, ModerationMetricsRecorder::countSlaBreachedItems)
                .description("Current moderation backlog grouped by bucket")
                .tag("bucket", "sla_breached")
                .register(meterRegistry);
    }

    public void recordAction(final ModerationAuditEventType eventType) {
        final Counter counter = actionCounters.get(eventType);
        if (counter == null) {
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
            return;
        }

        counter.increment();
    }

    double countPendingItems() {
        return moderationQueueRepository.countByStatus(ModerationStatus.PENDING);
    }

    double countUnderReviewItems() {
        return moderationQueueRepository.countByStatus(ModerationStatus.UNDER_REVIEW);
    }

    double countSlaBreachedItems() {
        final LocalDateTime cutoffTime = LocalDateTime.now().minusHours(moderationSlaHours);
        return moderationQueueRepository.countByStatusInAndSubmittedAtLessThanEqual(OPEN_STATUSES, cutoffTime);
    }
}
