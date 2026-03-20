package com.hipster.rating.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RatingMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> publishCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> consumerCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> antiEntropyCounters = new ConcurrentHashMap<>();

    public RatingMetricsRecorder(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordPublish(final String outcome) {
        increment(publishCounters, "rating.event.publish", "Rating event publish count", outcome);
    }

    public void recordConsumer(final String outcome) {
        increment(consumerCounters, "rating.event.consume", "Rating event consumer outcome count", outcome);
    }

    public void recordAntiEntropy(final String outcome) {
        increment(antiEntropyCounters, "rating.antientropy.run", "Rating anti-entropy run outcome count", outcome);
    }

    private void increment(final Map<String, Counter> counters,
                           final String meterName,
                           final String description,
                           final String outcome) {
        final String normalizedOutcome = outcome.toLowerCase(Locale.ROOT);
        final Counter counter = counters.computeIfAbsent(normalizedOutcome, key ->
                Counter.builder(meterName)
                        .description(description)
                        .tag("outcome", key)
                        .register(meterRegistry)
        );
        counter.increment();
    }
}
