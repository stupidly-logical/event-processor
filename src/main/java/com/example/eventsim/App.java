package com.example.eventsim;

import java.io.IOException;
import java.util.List;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws IOException {
        SimulationConfig config = SimulationConfig.defaults();
        MetricsRegistry metrics = new MetricsRegistry();
        SimulationClock clock = new SimulationClock(config.timeScale());
        RedisDedupCache redisDedupCache = new RedisDedupCache(config.redisTtlMs(), clock, metrics);
        SimulatedCircuitBreaker circuitBreaker = new SimulatedCircuitBreaker(
                config.circuitSlidingWindowSize(),
                config.circuitFailureThreshold(),
                config.circuitOpenWaitMs(),
                config.halfOpenProbeCalls(),
                clock,
                metrics
        );
        InMemoryPubSub pubSub = new InMemoryPubSub(metrics);
        EventAggregator aggregator = new EventAggregator(metrics);
        DataFlowDeduplicator deduplicator = new DataFlowDeduplicator(config.dataflowWindowMs(), clock, metrics, aggregator);
        pubSub.subscribe("dataflow-deduplicator", deduplicator::accept);
        DeadLetterQueue dlq = new DeadLetterQueue(config.dlqCapacity(), clock, circuitBreaker, metrics);
        EventPublisher publisher = new EventPublisher.Builder()
                .topicName(config.topicName())
                .redisDedupCache(redisDedupCache)
                .pubSub(pubSub)
                .circuitBreaker(circuitBreaker)
                .deadLetterQueue(dlq)
                .clock(clock)
                .metrics(metrics)
                .maxRetries(config.maxPublishRetries())
                .retryBaseDelayMs(config.retryBaseDelayMs())
                .retryMultiplier(config.retryMultiplier())
                .retryJitterRatio(config.retryJitterRatio())
                .build();
        dlq.setReplayHandler(publisher::replay);
        dlq.start();

        ScenarioController scenarioController = new ScenarioController(clock, pubSub, metrics);
        scenarioController.start();

        circuitBreaker.addStateChangeListener(stateChange ->
                System.out.printf("[%d] circuit-breaker %s -> %s%n",
                        stateChange.simulatedAtMs(),
                        stateChange.from(),
                        stateChange.to()));

        MonitoringHttpServer monitoringHttpServer = new MonitoringHttpServer(
                config.metricsPort(),
                metrics,
                clock,
                circuitBreaker,
                pubSub,
                dlq,
                scenarioController
        );
        monitoringHttpServer.start();

        List<GithubEvent> sampleEvents = GithubArchiveSamples.load();
        System.out.printf("Loaded %d GH Archive-like events. Metrics on http://localhost:%d/metrics%n",
                sampleEvents.size(),
                config.metricsPort());

        Thread ingestThread = new Thread(() -> runIngestLoop(config, sampleEvents, publisher, clock, metrics), "gharchive-ingest");
        ingestThread.start();
    }

    private static void runIngestLoop(SimulationConfig config,
                                      List<GithubEvent> sampleEvents,
                                      EventPublisher publisher,
                                      SimulationClock clock,
                                      MetricsRegistry metrics) {
        int index = 0;
        while (true) {
            GithubEvent event = sampleEvents.get(index % sampleEvents.size());
            PublishingUnit unit = publisher.toPublishingUnit(event);
            publisher.publishUnit(unit);
            metrics.incrementCounter("source_events_emitted_total", MapUtil.labels("type", event.type()), 1);

            if ((index % 20) == 0) {
                publisher.publishUnit(unit);
                metrics.incrementCounter("source_duplicate_publish_attempts_total");
            }

            index++;
            try {
                Thread.sleep(config.ingestIntervalRealMs());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            metrics.setGauge("source_last_emitted_at_ms", clock.now());
        }
    }
}
