package com.example.eventsim;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DataFlowDeduplicator {
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final long windowMs;
    private final SimulationClock clock;
    private final MetricsRegistry metrics;
    private final EventAggregator aggregator;

    public DataFlowDeduplicator(long windowMs,
                                SimulationClock clock,
                                MetricsRegistry metrics,
                                EventAggregator aggregator) {
        this.windowMs = windowMs;
        this.clock = clock;
        this.metrics = metrics;
        this.aggregator = aggregator;
    }

    public void accept(PublishingUnit unit) {
        evictExpired();
        Long previous = seen.putIfAbsent(unit.idempotencyKey(), clock.now());
        if (previous != null) {
            metrics.incrementCounter("dataflow_duplicates_caught_total");
            return;
        }
        metrics.incrementCounter("dataflow_clean_events_total");
        aggregator.accept(unit);
    }

    private void evictExpired() {
        long now = clock.now();
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > windowMs) {
                iterator.remove();
            }
        }
        metrics.setGauge("dataflow_state_store_entries", seen.size());
    }
}
