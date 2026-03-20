package com.hipster.reward.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class RewardMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Counter approvedInputCounter;
    private final Map<String, Counter> decisionCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> ledgerEntryCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> operationTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> asyncAccrualCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> outboxCreatedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> outboxPublishCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> outboxConsumeCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> outboxRecoverCounters = new ConcurrentHashMap<>();

    public RewardMetricsRecorder(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.approvedInputCounter = Counter.builder("reward.accrual.inputs")
                .description("Reward accrual input count")
                .register(meterRegistry);
    }

    public void recordApprovedInput() {
        incrementAfterCommit(approvedInputCounter);
    }

    public void recordDecision(final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = decisionCounters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder("reward.accrual.decisions")
                        .description("Reward accrual decision count")
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        incrementAfterCommit(counter);
    }

    public void recordLedgerEntry(final String entryType) {
        final String normalizedEntryType = entryType.toLowerCase(Locale.ROOT);
        final Counter counter = ledgerEntryCounters.computeIfAbsent(normalizedEntryType, key ->
                Counter.builder("reward.ledger.entries")
                        .description("Reward ledger entry count")
                        .tag("entry_type", key)
                        .register(meterRegistry)
        );
        incrementAfterCommit(counter);
    }

    public void recordOperationDuration(final String operation, final String outcome, final long durationNanos) {
        final String normalizedOperation = operation.toLowerCase(Locale.ROOT);
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final String timerKey = normalizedOperation + ":" + normalizedOutcome;

        final Timer timer = operationTimers.computeIfAbsent(timerKey, key ->
                Timer.builder("reward.operation.duration")
                        .description("Reward operation duration")
                        .tag("operation", normalizedOperation)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        );

        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordAsyncAccrualProcessing(final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = asyncAccrualCounters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder("reward.async.accrual.processing")
                        .description("Reward async accrual processing count")
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    public void recordOutboxCreated(final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = outboxCreatedCounters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder("reward.outbox.created")
                        .description("Reward accrual outbox creation count")
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        incrementAfterCommit(counter);
    }

    public void recordOutboxPublish(final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = outboxPublishCounters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder("reward.outbox.publish")
                        .description("Reward accrual outbox publish count")
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    public void recordOutboxConsume(final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = outboxConsumeCounters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder("reward.outbox.consume")
                        .description("Reward accrual outbox consumer count")
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    public void recordOutboxRecover(final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = outboxRecoverCounters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder("reward.outbox.recover")
                        .description("Reward accrual outbox recovery count")
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    private void incrementAfterCommit(final Counter counter) {
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
}
