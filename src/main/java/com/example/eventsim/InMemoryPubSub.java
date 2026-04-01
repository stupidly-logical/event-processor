package com.example.eventsim;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class InMemoryPubSub {
    private record DeliveryEnvelope(PublishingUnit unit, boolean duplicate) {
    }

    public static final class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message) {
            super(message);
        }
    }

    private static final class SubscriptionWorker implements Runnable {
        private final BlockingQueue<DeliveryEnvelope> queue = new LinkedBlockingQueue<>();
        private final Consumer<PublishingUnit> handler;
        private final MetricsRegistry metrics;
        private final Thread thread;
        private volatile boolean running = true;

        private SubscriptionWorker(String name, Consumer<PublishingUnit> handler, MetricsRegistry metrics) {
            this.handler = handler;
            this.metrics = metrics;
            this.thread = new Thread(this, "subscription-" + name);
            this.thread.start();
        }

        private void offer(DeliveryEnvelope envelope) {
            queue.offer(envelope);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    DeliveryEnvelope envelope = queue.take();
                    metrics.incrementCounter("pubsub_deliveries_total");
                    if (envelope.duplicate()) {
                        metrics.incrementCounter("pubsub_duplicate_deliveries_total");
                    }
                    handler.accept(envelope.unit());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private final List<SubscriptionWorker> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicInteger failureRatePercent = new AtomicInteger(5);
    private final AtomicInteger redeliveryPercent = new AtomicInteger(3);
    private final Random random = new Random(42);
    private final MetricsRegistry metrics;

    public InMemoryPubSub(MetricsRegistry metrics) {
        this.metrics = metrics;
        metrics.setGauge("pubsub_failure_rate_percent", failureRatePercent.get());
        metrics.setGauge("pubsub_redelivery_percent", redeliveryPercent.get());
    }

    public void subscribe(String name, Consumer<PublishingUnit> handler) {
        subscriptions.add(new SubscriptionWorker(name, handler, metrics));
    }

    public void publish(String topicName, PublishingUnit unit) {
        metrics.incrementCounter("pubsub_publish_attempts_total", MapUtil.labels("topic", topicName), 1);
        if (random.nextInt(100) < failureRatePercent.get()) {
            metrics.incrementCounter("pubsub_publish_failures_total", MapUtil.labels("topic", topicName), 1);
            throw new PublishFailedException("Simulated PubSub outage");
        }
        metrics.incrementCounter("pubsub_published_total", MapUtil.labels("topic", topicName), 1);
        for (SubscriptionWorker worker : subscriptions) {
            worker.offer(new DeliveryEnvelope(unit, false));
            if (random.nextInt(100) < redeliveryPercent.get()) {
                worker.offer(new DeliveryEnvelope(unit, true));
            }
        }
    }

    public void setFailureRatePercent(int rate) {
        failureRatePercent.set(Math.max(0, Math.min(100, rate)));
        metrics.setGauge("pubsub_failure_rate_percent", failureRatePercent.get());
    }

    public void setRedeliveryPercent(int rate) {
        redeliveryPercent.set(Math.max(0, Math.min(100, rate)));
        metrics.setGauge("pubsub_redelivery_percent", redeliveryPercent.get());
    }

    public Map<String, Integer> status() {
        return Map.of(
                "failureRatePercent", failureRatePercent.get(),
                "redeliveryPercent", redeliveryPercent.get()
        );
    }
}
