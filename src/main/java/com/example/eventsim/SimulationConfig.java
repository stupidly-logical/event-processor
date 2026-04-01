package com.example.eventsim;

public record SimulationConfig(
        String topicName,
        long redisTtlMs,
        long dataflowWindowMs,
        long circuitOpenWaitMs,
        int circuitSlidingWindowSize,
        double circuitFailureThreshold,
        int halfOpenProbeCalls,
        int maxPublishRetries,
        long retryBaseDelayMs,
        double retryMultiplier,
        double retryJitterRatio,
        int dlqCapacity,
        long timeScale,
        int metricsPort,
        long ingestIntervalRealMs,
        double publisherDuplicateAttemptRate) {

    public static SimulationConfig defaults() {
        return new SimulationConfig(
                "github-events",
                300_000L,
                120_000L,
                30_000L,
                20,
                0.50d,
                3,
                5,
                2_000L,
                2.0d,
                0.20d,
                500,
                10L,
                8080,
                75L,
                0.05d
        );
    }
}
