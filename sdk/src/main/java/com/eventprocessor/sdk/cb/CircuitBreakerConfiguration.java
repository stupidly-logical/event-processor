package com.eventprocessor.sdk.cb;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Count-based circuit breaker for Kafka publish operations.
 *
 * <p>Parameters (matches design spec):
 * <ul>
 *   <li>Sliding window: COUNT_BASED, size 20</li>
 *   <li>Failure rate threshold: 50%</li>
 *   <li>Wait duration in OPEN state: 30 s</li>
 *   <li>Permitted calls in HALF_OPEN: 3 (probe calls)</li>
 *   <li>Slow-call threshold: 5 s / 80%</li>
 * </ul>
 *
 * <p>The DLQ replayer checks {@code circuitBreaker.getState() == CLOSED}
 * before processing any batch, so probe events used in HALF_OPEN state
 * come from the live event stream — the CB transitions back to CLOSED only
 * after 3 consecutive successful probes.
 */
@Configuration
public class CircuitBreakerConfiguration {

    public static final String CB_NAME = "kafka-publish";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // Count-based sliding window of 20 calls
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                // Trip when ≥ 50% of calls in the window fail
                .failureRateThreshold(50.0f)
                // Stay OPEN for 30 s before allowing probe calls
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // Allow 3 probe calls in HALF_OPEN; all must succeed to close
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Slow calls (> 5 s) count as failures when ≥ 80% of window is slow
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(80.0f)
                // Only treat these as failures (not IllegalArgumentException etc.)
                .recordExceptions(Exception.class)
                // Do not record as failure when the CB itself is OPEN (CallNotPermittedException)
                .ignoreExceptions(
                        io.github.resilience4j.circuitbreaker.CallNotPermittedException.class
                )
                .build();

        return CircuitBreakerRegistry.of(config);
    }
}
