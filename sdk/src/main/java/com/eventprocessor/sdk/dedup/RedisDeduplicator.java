package com.eventprocessor.sdk.dedup;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Producer-side deduplicator backed by Redis SET NX.
 *
 * <p>Uses {@code SET key 1 PX 300000 NX} which is atomic — no WATCH/MULTI
 * needed and no race condition between check and set.
 *
 * <p>Key format: {@code dedup:<eventId>}
 * TTL: 300 seconds (5 min) — well beyond any realistic retry window.
 */
@Component
public class RedisDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(RedisDeduplicator.class);

    static final String KEY_PREFIX = "dedup:";
    static final Duration TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redis;
    private final Counter dedupHitCounter;
    private final Counter dedupMissCounter;

    public RedisDeduplicator(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.dedupHitCounter = Counter.builder("ep.dedup.hit")
                .description("Events suppressed by the deduplicator (key already present)")
                .register(meterRegistry);
        this.dedupMissCounter = Counter.builder("ep.dedup.miss")
                .description("Events that passed deduplication (new key set)")
                .register(meterRegistry);
    }

    /**
     * Attempts to claim the dedup slot for {@code eventId}.
     *
     * @return {@code true} if the event is new and should be published;
     *         {@code false} if it is a duplicate and must be dropped.
     */
    public boolean isNew(String eventId) {
        String key = KEY_PREFIX + eventId;
        // SET NX PX 300000 — returns true only when key did not exist
        Boolean set = redis.opsForValue().setIfAbsent(key, "1", TTL);
        boolean isNew = Boolean.TRUE.equals(set);

        if (isNew) {
            dedupMissCounter.increment();
            log.debug("Dedup MISS eventId={}", eventId);
        } else {
            dedupHitCounter.increment();
            log.debug("Dedup HIT (duplicate) eventId={}", eventId);
        }
        return isNew;
    }

    /**
     * Forcibly removes the dedup key — used by tests and the DLQ replayer
     * when a key needs to be cleared so a re-published event is not
     * inadvertently suppressed.
     */
    public void evict(String eventId) {
        redis.delete(KEY_PREFIX + eventId);
    }
}
