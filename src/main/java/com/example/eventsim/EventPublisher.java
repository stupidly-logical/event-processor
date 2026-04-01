package com.example.eventsim;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Random;
import java.util.UUID;

public final class EventPublisher {
    public static final class Builder {
        private String topicName;
        private RedisDedupCache redisDedupCache;
        private InMemoryPubSub pubSub;
        private SimulatedCircuitBreaker circuitBreaker;
        private DeadLetterQueue deadLetterQueue;
        private SimulationClock clock;
        private MetricsRegistry metrics;
        private int maxRetries;
        private long retryBaseDelayMs;
        private double retryMultiplier;
        private double retryJitterRatio;

        public Builder topicName(String topicName) {
            this.topicName = topicName;
            return this;
        }

        public Builder redisDedupCache(RedisDedupCache redisDedupCache) {
            this.redisDedupCache = redisDedupCache;
            return this;
        }

        public Builder pubSub(InMemoryPubSub pubSub) {
            this.pubSub = pubSub;
            return this;
        }

        public Builder circuitBreaker(SimulatedCircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
            return this;
        }

        public Builder deadLetterQueue(DeadLetterQueue deadLetterQueue) {
            this.deadLetterQueue = deadLetterQueue;
            return this;
        }

        public Builder clock(SimulationClock clock) {
            this.clock = clock;
            return this;
        }

        public Builder metrics(MetricsRegistry metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryBaseDelayMs(long retryBaseDelayMs) {
            this.retryBaseDelayMs = retryBaseDelayMs;
            return this;
        }

        public Builder retryMultiplier(double retryMultiplier) {
            this.retryMultiplier = retryMultiplier;
            return this;
        }

        public Builder retryJitterRatio(double retryJitterRatio) {
            this.retryJitterRatio = retryJitterRatio;
            return this;
        }

        public EventPublisher build() {
            return new EventPublisher(this);
        }
    }

    private final String topicName;
    private final RedisDedupCache redisDedupCache;
    private final InMemoryPubSub pubSub;
    private final SimulatedCircuitBreaker circuitBreaker;
    private final DeadLetterQueue deadLetterQueue;
    private final SimulationClock clock;
    private final MetricsRegistry metrics;
    private final int maxRetries;
    private final long retryBaseDelayMs;
    private final double retryMultiplier;
    private final double retryJitterRatio;
    private final Random jitterRandom = new Random(7);

    private EventPublisher(Builder builder) {
        this.topicName = builder.topicName;
        this.redisDedupCache = builder.redisDedupCache;
        this.pubSub = builder.pubSub;
        this.circuitBreaker = builder.circuitBreaker;
        this.deadLetterQueue = builder.deadLetterQueue;
        this.clock = builder.clock;
        this.metrics = builder.metrics;
        this.maxRetries = builder.maxRetries;
        this.retryBaseDelayMs = builder.retryBaseDelayMs;
        this.retryMultiplier = builder.retryMultiplier;
        this.retryJitterRatio = builder.retryJitterRatio;
    }

    public PublishingUnit toPublishingUnit(GithubEvent event) {
        return new PublishingUnit(UUID.randomUUID().toString(), event, clock.now());
    }

    public void publish(GithubEvent event) {
        publishUnit(toPublishingUnit(event), true);
    }

    public void publishUnit(PublishingUnit unit) {
        publishUnit(unit, true);
    }

    private void publishUnit(PublishingUnit unit, boolean routeFailuresToDlq) {
        if (redisDedupCache.alreadySeen(unit.idempotencyKey())) {
            metrics.incrementCounter("publisher_dropped_duplicates_total");
            return;
        }
        int attempts = 0;
        while (attempts <= maxRetries) {
            attempts++;
            metrics.incrementCounter("publisher_attempts_total");
            try {
                circuitBreaker.execute(() -> pubSub.publish(topicName, unit));
                metrics.incrementCounter("publisher_success_total");
                return;
            } catch (CallNotPermittedException callNotPermittedException) {
                metrics.incrementCounter("publisher_cb_open_total");
                if (routeFailuresToDlq) {
                    deadLetterQueue.enqueue(unit, DeadLetterQueue.Reason.CB_OPEN, attempts - 1);
                }
                return;
            } catch (Exception exception) {
                metrics.incrementCounter("publisher_failures_total");
                if (attempts > maxRetries) {
                    if (routeFailuresToDlq) {
                        deadLetterQueue.enqueue(unit, DeadLetterQueue.Reason.MAX_RETRIES_EXCEEDED, attempts - 1);
                    }
                    return;
                }
                long delay = backoffDelayForAttempt(attempts);
                metrics.incrementCounter("publisher_retries_total");
                clock.sleepSimulated(delay);
            }
        }
    }

    public DeadLetterQueue.ReplayResult replay(DeadLetterQueue.DeadLetterEntry entry) {
        if (circuitBreaker.state() != SimulatedCircuitBreaker.State.CLOSED) {
            return new DeadLetterQueue.ReplayResult(false, false);
        }
        try {
            circuitBreaker.execute(() -> pubSub.publish(topicName, entry.unit()));
            metrics.incrementCounter("publisher_replay_success_total");
            return new DeadLetterQueue.ReplayResult(true, false);
        } catch (CallNotPermittedException callNotPermittedException) {
            return new DeadLetterQueue.ReplayResult(false, false);
        } catch (Exception exception) {
            metrics.incrementCounter("publisher_replay_failures_total");
            boolean permanentlyFailed = entry.retryCount() >= 3;
            return new DeadLetterQueue.ReplayResult(false, permanentlyFailed);
        }
    }

    private long backoffDelayForAttempt(int attemptNumber) {
        double exponential = retryBaseDelayMs * Math.pow(retryMultiplier, Math.max(0, attemptNumber - 1));
        double jitter = 1.0 + ((jitterRandom.nextDouble() * 2.0) - 1.0) * retryJitterRatio;
        return Math.round(exponential * jitter);
    }
}
