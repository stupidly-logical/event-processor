package com.eventprocessor.producer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the producer emission loop.
 *
 * <p>All knobs are overridable via environment variables following
 * Spring Boot's relaxed binding:
 * <pre>
 *   producer.emission.topic           → PRODUCER_EMISSION_TOPIC
 *   producer.emission.rate-per-second → PRODUCER_EMISSION_RATE_PER_SECOND
 * </pre>
 */
@ConfigurationProperties(prefix = "producer.emission")
public record ProducerProperties(

        /**
         * Kafka topic to publish events to.
         * Default: {@code github-events}
         */
        @DefaultValue("github-events")
        String topic,

        /**
         * Target events published per second across the scheduler tick.
         * The tick runs every 1 000 ms; this controls the burst size per tick.
         * Default: 50 events/s
         */
        @DefaultValue("50")
        int ratePerSecond,

        /**
         * Whether the emission scheduler is enabled.
         * Set to {@code false} to pause production without restarting.
         * Default: {@code true}
         */
        @DefaultValue("true")
        boolean enabled,

        /**
         * Maximum consecutive publish failures before the scheduler
         * backs off for one full tick and logs a warning.
         * Default: 5
         */
        @DefaultValue("5")
        int maxConsecutiveFailures,

        /**
         * If {@code true}, duplicate eventIds are occasionally injected
         * (≈5% of events) to exercise the deduplicator in local/dev runs.
         * Should be {@code false} in production.
         * Default: {@code false}
         */
        @DefaultValue("false")
        boolean injectDuplicates
) {}
