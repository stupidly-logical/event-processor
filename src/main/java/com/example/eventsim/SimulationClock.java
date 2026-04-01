package com.example.eventsim;

public final class SimulationClock {
    private final long startRealMs = System.currentTimeMillis();
    private final long startSimulatedMs;
    private final long timeScale;

    public SimulationClock(long timeScale) {
        this.timeScale = Math.max(1L, timeScale);
        this.startSimulatedMs = System.currentTimeMillis();
    }

    public long now() {
        long elapsedReal = System.currentTimeMillis() - startRealMs;
        return startSimulatedMs + (elapsedReal * timeScale);
    }

    public void sleepSimulated(long simulatedDelayMs) {
        long realDelay = Math.max(1L, simulatedDelayMs / timeScale);
        try {
            Thread.sleep(realDelay);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public long toSimulatedDuration(long realDurationMs) {
        return realDurationMs * timeScale;
    }
}
