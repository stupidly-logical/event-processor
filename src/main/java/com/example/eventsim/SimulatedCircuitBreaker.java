package com.example.eventsim;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class SimulatedCircuitBreaker {
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    public record StateChange(State from, State to, long simulatedAtMs) {
    }

    private final int slidingWindowSize;
    private final SimulationClock clock;
    private final MetricsRegistry metrics;
    private final List<Consumer<StateChange>> listeners = new CopyOnWriteArrayList<>();
    private final CircuitBreaker delegate;
    private volatile State state = State.CLOSED;

    public SimulatedCircuitBreaker(int slidingWindowSize,
                                   double failureThreshold,
                                   long openWaitMs,
                                   int halfOpenProbeCalls,
                                   SimulationClock clock,
                                   MetricsRegistry metrics) {
        this.slidingWindowSize = slidingWindowSize;
        this.clock = clock;
        this.metrics = metrics;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(slidingWindowSize)
                .failureRateThreshold((float) (failureThreshold * 100.0d))
                .waitDurationInOpenState(Duration.ofMillis(Math.max(1L, openWaitMs / Math.max(1L, clock.toSimulatedDuration(1L)))))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(halfOpenProbeCalls)
                .recordExceptions(Exception.class)
                .build();
        this.delegate = CircuitBreakerRegistry.of(config).circuitBreaker("event-publisher");
        this.delegate.getEventPublisher().onStateTransition(this::onStateTransition);
        metrics.setGauge("circuit_breaker_state", MapUtil.labels("state", state.name()), 1);
        metrics.setGauge("circuit_breaker_state_code", 0);
    }

    public synchronized void addStateChangeListener(Consumer<StateChange> listener) {
        listeners.add(listener);
    }

    public synchronized State state() {
        state = mapState(delegate.getState());
        return state;
    }

    public void execute(CheckedRunnable runnable) throws Exception {
        try {
            delegate.executeCheckedRunnable(runnable::run);
            metrics.incrementCounter("circuit_breaker_calls_total", MapUtil.labels("result", "success"), 1);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException callNotPermittedException) {
            metrics.incrementCounter("circuit_breaker_calls_rejected_total");
            throw callNotPermittedException;
        } catch (Exception exception) {
            metrics.incrementCounter("circuit_breaker_calls_total", MapUtil.labels("result", "failure"), 1);
            metrics.setGauge("circuit_breaker_failure_rate_percent",
                    Math.round(delegate.getMetrics().getFailureRate() < 0 ? 0 : delegate.getMetrics().getFailureRate()));
            throw exception;
        }
    }

    private void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        State previous = mapState(event.getStateTransition().getFromState());
        State nextState = mapState(event.getStateTransition().getToState());
        this.state = nextState;
        metrics.incrementCounter("circuit_breaker_state_changes_total",
                MapUtil.labels("from", previous.name(), "to", nextState.name()), 1);
        metrics.setGauge("circuit_breaker_state_code", switch (nextState) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        });
        metrics.setGauge("circuit_breaker_state", MapUtil.labels("state", previous.name()), 0);
        metrics.setGauge("circuit_breaker_state", MapUtil.labels("state", nextState.name()), 1);
        StateChange stateChange = new StateChange(previous, nextState, clock.now());
        for (Consumer<StateChange> listener : listeners) {
            listener.accept(stateChange);
        }
    }

    private State mapState(CircuitBreaker.State delegateState) {
        return switch (delegateState) {
            case CLOSED -> State.CLOSED;
            case OPEN, FORCED_OPEN -> State.OPEN;
            case HALF_OPEN -> State.HALF_OPEN;
            case DISABLED, METRICS_ONLY -> State.CLOSED;
        };
    }
}
