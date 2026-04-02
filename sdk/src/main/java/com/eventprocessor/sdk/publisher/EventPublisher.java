package com.eventprocessor.sdk.publisher;

import com.eventprocessor.sdk.dedup.RedisDeduplicator;
import com.eventprocessor.sdk.dlq.DlqRouter;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Core SDK entry point for publishing {@link GitHubEvent} records.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Dedup check via Redis SET NX — duplicates are dropped and counted.</li>
 *   <li>Avro binary serialization.</li>
 *   <li>OTel span started; W3C traceparent injected into Kafka headers for
 *       async boundary tracing across the producer → consumer boundary.</li>
 *   <li>Kafka publish wrapped in the Resilience4j circuit breaker.</li>
 *   <li>On failure: route to DLQ via {@link DlqRouter}.</li>
 * </ol>
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final RedisDeduplicator deduplicator;
    private final DlqRouter dlqRouter;
    private final CircuitBreaker circuitBreaker;
    private final Tracer tracer;

    private final Counter publishedCounter;
    private final Counter duplicateCounter;
    private final Counter dlqCounter;
    private final Timer publishTimer;

    public EventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate,
                          RedisDeduplicator deduplicator,
                          DlqRouter dlqRouter,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.deduplicator = deduplicator;
        this.dlqRouter = dlqRouter;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("kafka-publish");
        this.tracer = GlobalOpenTelemetry.getTracer("event-processor-sdk", "1.0.0");

        this.publishedCounter = Counter.builder("ep.events.published")
                .description("Events successfully published to Kafka")
                .register(meterRegistry);
        this.duplicateCounter = Counter.builder("ep.events.duplicate")
                .description("Events dropped by the deduplicator")
                .register(meterRegistry);
        this.dlqCounter = Counter.builder("ep.events.dlq_routed")
                .description("Events sent to the DLQ after publish failure")
                .register(meterRegistry);
        this.publishTimer = Timer.builder("ep.events.publish.duration")
                .description("End-to-end publish latency including dedup and CB")
                .register(meterRegistry);
    }

    /**
     * Publish a single event to the specified Kafka topic.
     *
     * @param event the Avro event to publish
     * @param topic Kafka topic name
     */
    public void publish(GitHubEvent event, String topic) {
        publishTimer.record(() -> doPublish(event, topic));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void doPublish(GitHubEvent event, String topic) {
        // 1. Dedup
        if (!deduplicator.isNew(event.getEventId())) {
            duplicateCounter.increment();
            return;
        }

        // 2. Serialize
        byte[] payload = serialize(event);

        // 3. Trace span — propagate context into Kafka headers
        Span span = tracer.spanBuilder("kafka.publish")
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", topic)
                .setAttribute("event.id", event.getEventId())
                .setAttribute("event.type", event.getEventType().name())
                .startSpan();

        try (var scope = span.makeCurrent()) {
            // Inject W3C traceparent header so consumer can link the async span
            String traceparent = buildTraceparent(span);

            var message = MessageBuilder
                    .withPayload(payload)
                    .setHeader(KafkaHeaders.TOPIC, topic)
                    .setHeader(KafkaHeaders.KEY, event.getEventId())
                    .setHeader("traceparent", traceparent.getBytes(StandardCharsets.UTF_8))
                    .setHeader("event-type", event.getEventType().name()
                            .getBytes(StandardCharsets.UTF_8))
                    .build();

            // 4. Circuit breaker wraps the send.
            // .get() blocks until the broker ACKs (or times out), so the CB
            // observes the real outcome — not just "lambda returned without throwing".
            circuitBreaker.executeCheckedSupplier(() -> {
                var result = kafkaTemplate.send(message)
                        .get(4, java.util.concurrent.TimeUnit.SECONDS);
                publishedCounter.increment();
                log.debug("Published eventId={} topic={} offset={}",
                        event.getEventId(), topic,
                        result.getRecordMetadata() != null
                                ? result.getRecordMetadata().offset() : -1);
                return null;
            });

        } catch (Throwable ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            // 5. Route to DLQ
            dlqCounter.increment();
            dlqRouter.route(event, topic, ex.getMessage());
            log.error("Publish failed, routed to DLQ: eventId={} topic={} reason={}",
                    event.getEventId(), topic, ex.getMessage());
        } finally {
            span.end();
        }
    }

    private byte[] serialize(GitHubEvent event) {
        DatumWriter<GitHubEvent> writer = new SpecificDatumWriter<>(GitHubEvent.class);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(event, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Avro serialization failed", e);
        }
    }

    private String buildTraceparent(Span span) {
        var ctx = span.getSpanContext();
        return String.format("00-%s-%s-%s",
                ctx.getTraceId(),
                ctx.getSpanId(),
                ctx.getTraceFlags().asHex());
    }
}
