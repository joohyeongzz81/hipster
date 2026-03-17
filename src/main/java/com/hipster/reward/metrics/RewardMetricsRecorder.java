package com.hipster.reward.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RewardMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Counter approvedInputCounter;
    private final Map<String, Counter> decisionCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> ledgerEntryCounters = new ConcurrentHashMap<>();

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
