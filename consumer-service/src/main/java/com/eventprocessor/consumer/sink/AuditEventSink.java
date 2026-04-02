package com.eventprocessor.consumer.sink;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Audit sink — persists every event to an immutable audit trail.
 *
 * <p>Supports all event types: the audit log is a universal record of
 * every action that passed through the pipeline, enabling compliance
 * and forensic queries. In production this would write to an append-only
 * store (e.g. S3/GCS with object lock, or an audit-specific Kafka topic).
 *
 * <p>Structured log lines here stand in for that write so the pattern is
 * observable in Loki/Grafana. Each line includes {@code eventId} so log
 * queries can reconstruct the full trace alongside OTel spans.
 */
@Component
@ConditionalOnProperty(prefix = "consumer.sinks.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AuditEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(AuditEventSink.class);

    private final Counter auditedCounter;

    public AuditEventSink(MeterRegistry meterRegistry) {
        this.auditedCounter = Counter.builder("ep.audit.events_logged")
                .description("Events written to the audit trail")
                .tag("sink", "audit")
                .register(meterRegistry);
    }

    @Override
    public String name() { return "audit"; }

    @Override
    public boolean supports(EventType eventType) {
        return true;   // audit receives every event type
    }

    @Override
    public void process(GitHubEvent event) {
        auditedCounter.increment();

        // Structured audit log — index on eventId + actorLogin + eventType in ELK/Loki
        log.info("AUDIT eventId={} type={} actor={} repo={} occurredAt={}",
                event.getEventId(),
                event.getEventType(),
                event.getActorLogin(),
                event.getRepoName(),
                event.getOccurredAt());
    }
}
