package com.example.eventsim;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RedisDedupCache {
    private final ConcurrentHashMap<String, Long> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final SimulationClock clock;
    private final MetricsRegistry metrics;

    public RedisDedupCache(long ttlMs, SimulationClock clock, MetricsRegistry metrics) {
        this.ttlMs = ttlMs;
        this.clock = clock;
        this.metrics = metrics;
    }

    public boolean alreadySeen(String key) {
        evictExpired();
        Long existing = entries.putIfAbsent(key, clock.now());
        if (existing != null) {
            metrics.incrementCounter("redis_dedup_hits_total");
            return true;
        }
        metrics.incrementCounter("redis_dedup_misses_total");
        metrics.setGauge("redis_dedup_entries", entries.size());
        return false;
    }

    public void evictExpired() {
        long now = clock.now();
        Iterator<Map.Entry<String, Long>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > ttlMs) {
                iterator.remove();
                metrics.incrementCounter("redis_dedup_evictions_total");
            }
        }
        metrics.setGauge("redis_dedup_entries", entries.size());
    }
}
