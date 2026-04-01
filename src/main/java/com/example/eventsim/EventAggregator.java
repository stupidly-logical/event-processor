package com.example.eventsim;

public final class EventAggregator {
    private final MetricsRegistry metrics;

    public EventAggregator(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    public void accept(PublishingUnit unit) {
        metrics.incrementCounter("aggregator_events_processed_total");
        processSink("billing", unit);
        processSink("audit", unit);
        processSink("analytics", unit);
    }

    private void processSink(String sink, PublishingUnit unit) {
        metrics.incrementCounter("sink_events_processed_total", MapUtil.labels("sink", sink), 1);
        metrics.incrementCounter("sink_events_by_type_total",
                MapUtil.labels("sink", sink, "type", unit.event().type()), 1);
    }
}
