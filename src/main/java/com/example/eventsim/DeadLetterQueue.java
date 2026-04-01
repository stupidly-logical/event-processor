package com.example.eventsim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DeadLetterQueue {
    public enum Reason {
        CB_OPEN,
        MAX_RETRIES_EXCEEDED
    }

    public record DeadLetterEntry(
            PublishingUnit unit,
            Reason reason,
            long timestampMs,
            int retryCount) {

        public DeadLetterEntry withRetryCount(int nextRetryCount) {
            return new DeadLetterEntry(unit, reason, timestampMs, nextRetryCount);
        }
    }

    @FunctionalInterface
    public interface ReplayHandler {
        ReplayResult replay(DeadLetterEntry entry);
    }

    public record ReplayResult(boolean successful, boolean permanentlyFailed) {
    }

    private final int capacity;
    private final Deque<DeadLetterEntry> queue = new ArrayDeque<>();
    private final SimulationClock clock;
    private final SimulatedCircuitBreaker circuitBreaker;
    private final MetricsRegistry metrics;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile ReplayHandler replayHandler;

    public DeadLetterQueue(int capacity,
                           SimulationClock clock,
                           SimulatedCircuitBreaker circuitBreaker,
                           MetricsRegistry metrics) {
        this.capacity = capacity;
        this.clock = clock;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::attemptReplay, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void enqueue(PublishingUnit unit, Reason reason, int retryCount) {
        if (queue.size() >= capacity) {
            queue.removeFirst();
            metrics.incrementCounter("dlq_permanently_failed_total", MapUtil.labels("reason", "capacity_eviction"), 1);
        }
        queue.addLast(new DeadLetterEntry(unit, reason, clock.now(), retryCount));
        metrics.incrementCounter("dlq_enqueued_total", MapUtil.labels("reason", reason.name()), 1);
        metrics.setGauge("dlq_depth", queue.size());
    }

    public synchronized List<DeadLetterEntry> snapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized Optional<DeadLetterEntry> peek() {
        return Optional.ofNullable(queue.peekFirst());
    }

    public void setReplayHandler(ReplayHandler replayHandler) {
        this.replayHandler = replayHandler;
    }

    private void attemptReplay() {
        if (replayHandler == null || circuitBreaker.state() != SimulatedCircuitBreaker.State.CLOSED) {
            return;
        }
        DeadLetterEntry entry;
        synchronized (this) {
            entry = queue.peekFirst();
        }
        if (entry == null) {
            return;
        }
        ReplayResult result = replayHandler.replay(entry.withRetryCount(entry.retryCount() + 1));
        synchronized (this) {
            if (result.successful()) {
                queue.removeFirst();
                metrics.incrementCounter("dlq_replayed_successfully_total", MapUtil.labels("reason", entry.reason().name()), 1);
            } else if (result.permanentlyFailed()) {
                queue.removeFirst();
                metrics.incrementCounter("dlq_permanently_failed_total", MapUtil.labels("reason", entry.reason().name()), 1);
            } else {
                queue.removeFirst();
                queue.addLast(entry.withRetryCount(entry.retryCount() + 1));
            }
            metrics.setGauge("dlq_depth", queue.size());
        }
    }
}
