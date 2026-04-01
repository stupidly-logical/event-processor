package com.example.eventsim;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScenarioController {
    private final SimulationClock clock;
    private final InMemoryPubSub pubSub;
    private final MetricsRegistry metrics;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final long startedAtMs;
    private volatile String phase = "happy_path";

    public ScenarioController(SimulationClock clock, InMemoryPubSub pubSub, MetricsRegistry metrics) {
        this.clock = clock;
        this.pubSub = pubSub;
        this.metrics = metrics;
        this.startedAtMs = clock.now();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    public String phase() {
        return phase;
    }

    private void tick() {
        long elapsedSeconds = (clock.now() - startedAtMs) / 1_000L;
        if (elapsedSeconds < 40) {
            applyPhase("happy_path", 5, 3);
        } else if (elapsedSeconds < 70) {
            applyPhase("pubsub_outage", 85, 3);
        } else if (elapsedSeconds < 100) {
            applyPhase("duplicate_burst_recovery", 20, 18);
        } else {
            applyPhase("steady_state", 2, 3);
        }
    }

    private void applyPhase(String nextPhase, int failureRate, int redeliveryRate) {
        if (!phase.equals(nextPhase)) {
            metrics.incrementCounter("simulation_phase_changes_total",
                    MapUtil.labels("phase", nextPhase), 1);
        }
        phase = nextPhase;
        pubSub.setFailureRatePercent(failureRate);
        pubSub.setRedeliveryPercent(redeliveryRate);
        for (String knownPhase : new String[]{"happy_path", "pubsub_outage", "duplicate_burst_recovery", "steady_state"}) {
            metrics.setGauge("simulation_phase", Map.of("phase", knownPhase), knownPhase.equals(nextPhase) ? 1 : 0);
        }
    }
}
