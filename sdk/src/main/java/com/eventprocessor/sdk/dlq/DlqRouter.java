package com.eventprocessor.sdk.dlq;

import com.eventprocessor.sdk.model.DlqEvent;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Routes a failed {@link GitHubEvent} to the PostgreSQL DLQ.
 *
 * <p>Serializes the Avro record to binary, captures the current OTel
 * trace context (W3C traceparent), and persists a {@link DlqEvent} row.
 */
@Component
public class DlqRouter {

    private static final Logger log = LoggerFactory.getLogger(DlqRouter.class);

    private final DlqEventRepository repository;
    private final Counter dlqRoutedCounter;

    public DlqRouter(DlqEventRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.dlqRoutedCounter = Counter.builder("ep.dlq.routed")
                .description("Events routed to the DLQ")
                .register(meterRegistry);
    }

    /**
     * Persists the event to the DLQ table.
     *
     * @param event         the failed Avro event
     * @param topic         the Kafka topic that was targeted
     * @param failureReason human-readable reason (exception message)
     */
    @Transactional
    public void route(GitHubEvent event, String topic, String failureReason) {
        if (repository.existsByEventId(event.getEventId())) {
            log.warn("DLQ already contains eventId={}, skipping duplicate insert", event.getEventId());
            return;
        }

        byte[] avroBytes = serialize(event);
        String traceContext = extractTraceContext();

        DlqEvent dlqEvent = new DlqEvent(
                event.getEventId(),
                event.getEventType().name(),
                topic,
                avroBytes,
                traceContext,
                failureReason,
                event.getOccurredAt()
        );

        repository.save(dlqEvent);
        dlqRoutedCounter.increment();

        log.warn("Routed to DLQ: eventId={} type={} topic={} reason={}",
                event.getEventId(), event.getEventType(), topic, failureReason);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private byte[] serialize(GitHubEvent event) {
        DatumWriter<GitHubEvent> writer = new SpecificDatumWriter<>(GitHubEvent.class);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(event, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize GitHubEvent for DLQ", e);
        }
    }

    /**
     * Extracts the W3C traceparent from the current OTel span context.
     * Returns null if no active span is present (e.g. in tests).
     */
    private String extractTraceContext() {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return null;
        }
        var ctx = span.getSpanContext();
        // Format: 00-<traceId>-<spanId>-<flags>
        return String.format("00-%s-%s-%s",
                ctx.getTraceId(),
                ctx.getSpanId(),
                ctx.getTraceFlags().asHex());
    }
}
