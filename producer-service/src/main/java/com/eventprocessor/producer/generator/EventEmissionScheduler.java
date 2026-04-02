package com.eventprocessor.producer.generator;

import com.eventprocessor.producer.config.ProducerProperties;
import com.eventprocessor.sdk.model.GitHubEvent;
import com.eventprocessor.sdk.publisher.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the event stream by calling {@link GitHubArchiveEventGenerator}
 * on a fixed 1-second tick and forwarding each event to {@link EventPublisher}.
 *
 * <p>Design highlights:
 * <ul>
 *   <li><strong>Rate control</strong> — {@code ratePerSecond} events are published
 *       per tick. Backed by {@link ProducerProperties} so the rate is adjustable
 *       without a restart via Spring Boot Actuator / config refresh.</li>
 *   <li><strong>Duplicate injection</strong> — when {@code injectDuplicates=true}
 *       (dev/load-test mode) ≈5% of events re-use a recently seen eventId, driving
 *       the Redis deduplicator counter up so you can observe it on Grafana.</li>
 *   <li><strong>Back-pressure</strong> — if consecutive failures exceed
 *       {@code maxConsecutiveFailures} the tick is skipped entirely and a warning
 *       counter incremented. The circuit breaker handles the actual open/half-open
 *       transition; this just prevents log flooding.</li>
 *   <li><strong>OTel root span</strong> — each tick creates a parent span
 *       {@code producer.tick} containing child {@code kafka.publish} spans so the
 *       full fan-out is visible in Jaeger as a single trace tree.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(ProducerProperties.class)
public class EventEmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventEmissionScheduler.class);

    private final GitHubArchiveEventGenerator generator;
    private final EventPublisher publisher;
    private final ProducerProperties props;
    private final Tracer tracer;

    // ── Metrics ──────────────────────────────────────────────────────────────
    private final Counter tickCounter;
    private final Counter tickSkippedCounter;
    private final Timer tickTimer;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong totalEmitted = new AtomicLong(0);

    // Ring buffer of recent eventIds for duplicate injection (last 20 ids)
    private final String[] recentIds = new String[20];
    private int ringIdx = 0;

    public EventEmissionScheduler(GitHubArchiveEventGenerator generator,
                                  EventPublisher publisher,
                                  ProducerProperties props,
                                  MeterRegistry meterRegistry) {
        this.generator = generator;
        this.publisher = publisher;
        this.props = props;
        this.tracer = GlobalOpenTelemetry.getTracer("producer-service", "1.0.0");

        this.tickCounter = Counter.builder("ep.producer.ticks")
                .description("Scheduler ticks executed")
                .register(meterRegistry);
        this.tickSkippedCounter = Counter.builder("ep.producer.ticks.skipped")
                .description("Ticks skipped due to excessive consecutive failures")
                .register(meterRegistry);
        this.tickTimer = Timer.builder("ep.producer.tick.duration")
                .description("Wall-clock time to emit one full batch")
                .register(meterRegistry);

        // Gauge — total events emitted since startup
        Gauge.builder("ep.producer.total_emitted", totalEmitted, AtomicLong::doubleValue)
                .description("Cumulative events emitted since startup")
                .register(meterRegistry);
    }

    /**
     * Main emission loop — fires every 1 000 ms.
     * {@code fixedDelay} (not {@code fixedRate}) prevents overlapping ticks
     * if a burst takes longer than 1 s due to Kafka back-pressure.
     */
    @Scheduled(fixedDelayString = "${producer.emission.tick-ms:1000}")
    public void tick() {
        if (!props.enabled()) {
            return;
        }

        // Skip if failures are too hot
        if (consecutiveFailures.get() >= props.maxConsecutiveFailures()) {
            tickSkippedCounter.increment();
            log.warn("Skipping emission tick — {} consecutive failures (circuit may be open)",
                    consecutiveFailures.get());
            consecutiveFailures.decrementAndGet();  // decay back toward zero each skipped tick
            return;
        }

        tickTimer.record(this::emitBatch);
        tickCounter.increment();
    }

    // ── Batch logic ──────────────────────────────────────────────────────────

    private void emitBatch() {
        int batchSize = props.ratePerSecond();

        Span tickSpan = tracer.spanBuilder("producer.tick")
                .setAttribute("batch.size", batchSize)
                .startSpan();

        int emitted = 0;
        int errors  = 0;

        try (var scope = tickSpan.makeCurrent()) {
            for (int i = 0; i < batchSize; i++) {
                try {
                    GitHubEvent event = maybeInjectDuplicate(generator.generate());
                    publisher.publish(event, props.topic());
                    trackRecent(event.getEventId());
                    emitted++;
                } catch (Exception ex) {
                    errors++;
                    tickSpan.recordException(ex);
                    log.debug("Emission error in tick: {}", ex.getMessage());
                }
            }

            totalEmitted.addAndGet(emitted);

            if (errors == 0) {
                consecutiveFailures.set(0);
            } else {
                consecutiveFailures.addAndGet(errors);
                tickSpan.setStatus(StatusCode.ERROR,
                        errors + " of " + batchSize + " events failed");
            }

            log.debug("Tick: emitted={} errors={} total={}", emitted, errors, totalEmitted.get());

        } finally {
            tickSpan.end();
        }
    }

    // ── Duplicate injection (dev/load-test only) ──────────────────────────────

    private GitHubEvent maybeInjectDuplicate(GitHubEvent event) {
        if (!props.injectDuplicates()) { return event; }

        // ~5% chance: pick a random recent eventId and stamp it onto this event
        if (Math.random() < 0.05 && recentIds[ringIdx % 20] != null) {
            String existingId = recentIds[(int) (Math.random() * 20)];
            if (existingId != null) {
                return GitHubEvent.newBuilder(event)
                        .setEventId(existingId)
                        .build();
            }
        }
        return event;
    }

    private void trackRecent(String eventId) {
        recentIds[ringIdx % 20] = eventId;
        ringIdx++;
    }
}
