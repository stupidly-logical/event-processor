package com.eventprocessor.consumer;

import com.eventprocessor.consumer.dedup.WindowedDeduplicator;
import com.eventprocessor.consumer.sink.EventSink;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Kafka listener that drives the full consumer pipeline for each record:
 *
 * <ol>
 *   <li><strong>Deserialize</strong> — Avro binary bytes → {@link GitHubEvent}.</li>
 *   <li><strong>Trace context restore</strong> — extract the W3C {@code traceparent}
 *       header injected by the producer, create a linked consumer span so the
 *       async Kafka boundary is visible as a single trace in Jaeger.</li>
 *   <li><strong>Consumer-side dedup</strong> — 120 s windowed Redis SET NX check.
 *       Duplicates (e.g. after a rebalance) are ACKed and silently dropped.</li>
 *   <li><strong>Fan-out</strong> — iterate over all registered {@link EventSink}
 *       beans. Sinks that return {@code supports()==false} for the event type are
 *       skipped. Sink failures are isolated: one failing sink does not prevent
 *       the others from running, but the failure is counted and the span marked
 *       with an error.</li>
 *   <li><strong>Manual ACK</strong> — offset is committed via
 *       {@link Acknowledgment#acknowledge()} only after all sinks have been
 *       attempted. If deserialization throws, the error handler retries up to 3
 *       times before routing the raw bytes to the DLQ.</li>
 * </ol>
 */
@Component
public class GitHubEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GitHubEventConsumer.class);

    private final WindowedDeduplicator deduplicator;
    private final List<EventSink> sinks;
    private final Tracer tracer;

    private final Counter consumedCounter;
    private final Counter dedupDroppedCounter;
    private final Counter sinkErrorCounter;
    private final Timer consumeTimer;

    public GitHubEventConsumer(WindowedDeduplicator deduplicator,
                               List<EventSink> sinks,
                               MeterRegistry meterRegistry) {
        this.deduplicator = deduplicator;
        this.sinks = sinks;
        this.tracer = GlobalOpenTelemetry.getTracer("consumer-service", "1.0.0");

        this.consumedCounter = Counter.builder("ep.consumer.consumed")
                .description("Events successfully consumed and fanned out")
                .register(meterRegistry);
        this.dedupDroppedCounter = Counter.builder("ep.consumer.dedup_dropped")
                .description("Events dropped by the consumer windowed deduplicator")
                .register(meterRegistry);
        this.sinkErrorCounter = Counter.builder("ep.consumer.sink_errors")
                .description("Individual sink failures during fan-out")
                .register(meterRegistry);
        this.consumeTimer = Timer.builder("ep.consumer.process.duration")
                .description("End-to-end processing time per record (dedup + fan-out)")
                .register(meterRegistry);
    }

    /**
     * Main listener method.
     *
     * <p>{@code concurrency} is set to 3 in {@code KafkaConsumerConfig} matching
     * the partition count, so up to 3 instances of this method run in parallel —
     * one per partition, in strict partition-order.
     */
    @KafkaListener(
            topics = "${consumer.kafka.topic:github-events}",
            groupId = "${consumer.kafka.group-id:consumer-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        consumeTimer.record(() -> doConsume(record, ack));
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private void doConsume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        // 1. Deserialize — if this throws, the error handler retries / routes to DLQ
        GitHubEvent event = deserialize(record.value());

        // 2. Restore trace context from the W3C traceparent header
        Context parentContext = extractTraceContext(record);
        Span span = tracer.spanBuilder("kafka.consume")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parentContext)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", record.topic())
                .setAttribute("messaging.kafka.partition", record.partition())
                .setAttribute("messaging.kafka.offset", record.offset())
                .setAttribute("event.id", event.getEventId())
                .setAttribute("event.type", event.getEventType().name())
                .startSpan();

        try (var scope = span.makeCurrent()) {
            // 3. Consumer-side dedup
            if (!deduplicator.isNew(event.getEventId())) {
                dedupDroppedCounter.increment();
                log.debug("Consumer dedup drop: eventId={} partition={} offset={}",
                        event.getEventId(), record.partition(), record.offset());
                ack.acknowledge();  // must ack duplicates or the offset stalls
                return;
            }

            // 4. Fan-out to all registered sinks
            boolean anyError = false;
            for (EventSink sink : sinks) {
                if (!sink.supports(event.getEventType())) continue;

                try {
                    sink.process(event);
                } catch (Exception ex) {
                    sinkErrorCounter.increment();
                    span.recordException(ex);
                    log.error("Sink '{}' failed for eventId={}: {}",
                            sink.name(), event.getEventId(), ex.getMessage(), ex);
                    anyError = true;
                    // Continue to next sink — partial delivery is better than none
                }
            }

            if (anyError) {
                span.setStatus(StatusCode.ERROR, "One or more sinks failed");
            }

            consumedCounter.increment();
            log.debug("Consumed eventId={} type={} partition={} offset={}",
                    event.getEventId(), event.getEventType(), record.partition(), record.offset());

        } finally {
            // 5. Manual ACK — commit the offset regardless of sink errors.
            //    A sink failure is not a reason to reprocess — the DLQ handles
            //    persistent failures at the Kafka level via the error handler.
            ack.acknowledge();
            span.end();
        }
    }

    // ── OTel context extraction ───────────────────────────────────────────────

    /**
     * Extracts the W3C traceparent header set by the producer's
     * {@link com.eventprocessor.sdk.publisher.EventPublisher} and returns a
     * {@link Context} that the consumer span can link to as its parent.
     *
     * <p>Format: {@code 00-<traceId>-<spanId>-<flags>}
     */
    private Context extractTraceContext(ConsumerRecord<String, byte[]> record) {
        Header header = record.headers().lastHeader("traceparent");
        if (header == null) return Context.current();

        String traceparent = new String(header.value(), StandardCharsets.UTF_8);
        String[] parts = traceparent.split("-");
        if (parts.length != 4) return Context.current();

        try {
            SpanContext remoteCtx = SpanContext.createFromRemoteParent(
                    parts[1],                                          // traceId
                    parts[2],                                          // spanId
                    TraceFlags.fromHex(parts[3], 0),                   // flags
                    TraceState.getDefault()
            );
            return Context.current().with(Span.wrap(remoteCtx));
        } catch (Exception e) {
            log.debug("Failed to parse traceparent header '{}': {}", traceparent, e.getMessage());
            return Context.current();
        }
    }

    // ── Avro deserialization ──────────────────────────────────────────────────

    private GitHubEvent deserialize(byte[] bytes) {
        DatumReader<GitHubEvent> reader = new SpecificDatumReader<>(GitHubEvent.class);
        try {
            var decoder = DecoderFactory.get()
                    .binaryDecoder(new ByteArrayInputStream(bytes), null);
            return reader.read(null, decoder);
        } catch (IOException e) {
            throw new org.apache.avro.AvroRuntimeException("Avro deserialization failed", e);
        }
    }
}
