package com.eventprocessor.sdk.dlq;

import com.eventprocessor.sdk.model.DlqEvent;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled replayer that drains the DLQ in FIFO order.
 *
 * <p>Design constraints implemented:
 * <ul>
 *   <li>Only runs when the circuit breaker is CLOSED.</li>
 *   <li>Processes at most {@code BATCH_SIZE} events per tick (rate limit).</li>
 *   <li>Re-arms Redis TTL for events older than 4 minutes before publishing
 *       (prevents the deduplicator from silently dropping them a second time).</li>
 *   <li>On publish failure the event is re-enqueued at head (retry count
 *       incremented, status → PENDING) to preserve FIFO ordering.</li>
 *   <li>Events that exceed {@code MAX_RETRIES} are promoted to DEAD.</li>
 * </ul>
 */
@Component
public class DlqReplayer {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayer.class);

    static final int BATCH_SIZE = 10;
    static final int MAX_RETRIES = 5;

    /**
     * Events created more than this long ago may have had their Redis dedup
     * key expire, so the TTL must be re-armed before replay.
     */
    static final Duration TTL_REARM_THRESHOLD = Duration.ofMinutes(4);
    static final Duration REDIS_DEDUP_TTL = Duration.ofSeconds(300);

    private final DlqEventRepository repository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final CircuitBreaker circuitBreaker;
    private final Tracer tracer;
    private final Counter replayedCounter;
    private final Counter deadLetterCounter;

    public DlqReplayer(DlqEventRepository repository,
                       KafkaTemplate<String, byte[]> kafkaTemplate,
                       StringRedisTemplate redisTemplate,
                       CircuitBreakerRegistry circuitBreakerRegistry,
                       MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("kafka-publish");
        this.tracer = GlobalOpenTelemetry.getTracer("event-processor-sdk-replayer", "1.0.0");
        this.replayedCounter = Counter.builder("ep.dlq.replayed")
                .description("DLQ events successfully replayed")
                .register(meterRegistry);
        this.deadLetterCounter = Counter.builder("ep.dlq.dead")
                .description("DLQ events promoted to DEAD after max retries")
                .register(meterRegistry);
    }

    /**
     * Runs every 5 seconds. Skips the tick if the circuit breaker is not CLOSED.
     */
    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void replay() {
        if (circuitBreaker.getState() != CircuitBreaker.State.CLOSED) {
            log.debug("DlqReplayer skipping tick — circuit breaker state={}",
                    circuitBreaker.getState());
            return;
        }

        var batch = repository.findOldestPending(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        log.info("DlqReplayer processing batch size={}", batch.size());

        for (DlqEvent dlqEvent : batch) {
            processSingle(dlqEvent);
        }
    }

    // ── Per-event logic ──────────────────────────────────────────────────────

    private void processSingle(DlqEvent dlqEvent) {
        Span span = tracer.spanBuilder("dlq.replay")
                .setAttribute("event.id", dlqEvent.getEventId())
                .setAttribute("event.type", dlqEvent.getEventType())
                .setAttribute("dlq.retry_count", dlqEvent.getRetryCount())
                .startSpan();

        try (var scope = span.makeCurrent()) {
            dlqEvent.markReplaying();
            repository.save(dlqEvent);

            rearmRedisTtlIfNeeded(dlqEvent);

            GitHubEvent event = deserialize(dlqEvent.getPayload());

            circuitBreaker.executeCheckedSupplier(() -> {
                kafkaTemplate.send(dlqEvent.getTopic(), event.getEventId(), dlqEvent.getPayload())
                        .get(4, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            });

            dlqEvent.markReplayed();
            repository.save(dlqEvent);
            replayedCounter.increment();
            log.info("Replayed eventId={} attempt={}", dlqEvent.getEventId(), dlqEvent.getRetryCount());

        } catch (Throwable ex) {
            span.recordException(ex);
            handleFailure(dlqEvent, ex);
        } finally {
            span.end();
        }
    }

    /**
     * Re-arms the Redis dedup key so the consumer does not drop this event
     * as a duplicate when it arrives from the DLQ path.
     */
    private void rearmRedisTtlIfNeeded(DlqEvent dlqEvent) {
        Duration age = Duration.between(dlqEvent.getOriginalOccurredAt(), Instant.now());
        if (age.compareTo(TTL_REARM_THRESHOLD) > 0) {
            String redisKey = "dedup:" + dlqEvent.getEventId();
            // SET key 1 EX 300 XX — only update TTL if key already exists.
            // If the key is gone we intentionally skip re-arm: consumer will
            // treat it as a new event (desired — the original was never processed).
            Boolean exists = redisTemplate.hasKey(redisKey);
            if (Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(redisKey, REDIS_DEDUP_TTL);
                log.debug("Re-armed Redis TTL for eventId={}", dlqEvent.getEventId());
            }
        }
    }

    /**
     * On failure: increment retry count or promote to DEAD.
     * Re-setting status to PENDING preserves FIFO order because the
     * replayer always fetches by created_at ASC.
     */
    private void handleFailure(DlqEvent dlqEvent, Throwable ex) {
        if (dlqEvent.getRetryCount() >= MAX_RETRIES) {
            dlqEvent.markDead();
            deadLetterCounter.increment();
            log.error("EventId={} promoted to DEAD after {} retries",
                    dlqEvent.getEventId(), dlqEvent.getRetryCount());
        } else {
            dlqEvent.incrementRetry(ex.getMessage());
            log.warn("Replay failed eventId={} attempt={} reason={}",
                    dlqEvent.getEventId(), dlqEvent.getRetryCount(), ex.getMessage());
        }
        repository.save(dlqEvent);
    }

    // ── Deserialization ──────────────────────────────────────────────────────

    private GitHubEvent deserialize(byte[] bytes) throws IOException {
        DatumReader<GitHubEvent> reader = new SpecificDatumReader<>(GitHubEvent.class);
        var decoder = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bytes), null);
        return reader.read(null, decoder);
    }
}
