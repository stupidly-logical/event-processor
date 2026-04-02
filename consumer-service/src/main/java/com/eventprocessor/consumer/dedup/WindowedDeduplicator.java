package com.eventprocessor.consumer.dedup;

import com.eventprocessor.consumer.config.ConsumerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumer-side deduplication window backed by Redis SET NX.
 *
 * <p>This is intentionally a <em>separate layer</em> from the producer's
 * {@link com.eventprocessor.sdk.dedup.RedisDeduplicator}:
 *
 * <table>
 *   <tr><th>Layer</th><th>TTL</th><th>Key prefix</th><th>Purpose</th></tr>
 *   <tr><td>Producer</td><td>300 s</td><td>{@code dedup:}</td>
 *       <td>Suppress duplicate publishes to Kafka</td></tr>
 *   <tr><td>Consumer</td><td>120 s</td><td>{@code consumer-dedup:}</td>
 *       <td>Suppress duplicate processing on rebalance / at-least-once re-delivery</td></tr>
 * </table>
 *
 * <p>The shorter consumer window (120 s) is intentional: the producer window
 * guarantees no duplicates in steady state; the consumer window is a safety net
 * only for the narrow at-least-once re-delivery window caused by consumer
 * rebalancing. Keeping it shorter reduces Redis memory pressure.
 *
 * <p>Key format: {@code consumer-dedup:<eventId>}
 */
@Component
public class WindowedDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(WindowedDeduplicator.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration window;
    private final Counter hitCounter;
    private final Counter missCounter;

    public WindowedDeduplicator(StringRedisTemplate redis,
                                ConsumerProperties props,
                                MeterRegistry meterRegistry) {
        this.redis = redis;
        this.keyPrefix = props.dedup().keyPrefix();
        this.window = Duration.ofSeconds(props.dedup().windowSeconds());

        this.hitCounter = Counter.builder("ep.consumer.dedup.hit")
                .description("Events suppressed by the consumer windowed deduplicator")
                .register(meterRegistry);
        this.missCounter = Counter.builder("ep.consumer.dedup.miss")
                .description("Events that passed consumer deduplication")
                .register(meterRegistry);
    }

    /**
     * Attempts to claim the dedup slot for {@code eventId} within the
     * configured window.
     *
     * @return {@code true} if the event is new and should be processed;
     *         {@code false} if it is a within-window duplicate.
     */
    public boolean isNew(String eventId) {
        String key = keyPrefix + eventId;
        Boolean set = redis.opsForValue().setIfAbsent(key, "1", window);
        boolean isNew = Boolean.TRUE.equals(set);

        if (isNew) {
            missCounter.increment();
            log.debug("Consumer dedup MISS eventId={} window={}s", eventId, window.getSeconds());
        } else {
            hitCounter.increment();
            log.debug("Consumer dedup HIT (duplicate) eventId={}", eventId);
        }
        return isNew;
    }

    /** Test / administrative eviction. */
    public void evict(String eventId) {
        redis.delete(keyPrefix + eventId);
    }
}
