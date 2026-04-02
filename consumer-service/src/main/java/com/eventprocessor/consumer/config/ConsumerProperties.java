package com.eventprocessor.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the consumer pipeline.
 *
 * <p>Env-var overrides follow Spring Boot relaxed binding:
 * <pre>
 *   consumer.kafka.group-id            → CONSUMER_KAFKA_GROUP_ID
 *   consumer.dedup.window-seconds      → CONSUMER_DEDUP_WINDOW_SECONDS
 *   consumer.sinks.billing.enabled     → CONSUMER_SINKS_BILLING_ENABLED
 * </pre>
 */
@ConfigurationProperties(prefix = "consumer")
public record ConsumerProperties(

        @DefaultValue
        KafkaProps kafka,

        @DefaultValue
        DedupProps dedup,

        @DefaultValue
        SinksProps sinks

) {

    public record KafkaProps(
            /** Consumer group ID. */
            @DefaultValue("consumer-service-group")
            String groupId,

            /** Topic to consume from. */
            @DefaultValue("github-events")
            String topic,

            /** Number of concurrent listener threads (should equal partition count). */
            @DefaultValue("3")
            int concurrency
    ) {}

    public record DedupProps(
            /**
             * TTL for the consumer-side dedup window in seconds.
             * Independent of the producer-side 300s window.
             * Default: 120s
             */
            @DefaultValue("120")
            int windowSeconds,

            /**
             * Redis key prefix for consumer-side dedup keys.
             * Must NOT collide with the producer's {@code dedup:} namespace.
             */
            @DefaultValue("consumer-dedup:")
            String keyPrefix
    ) {}

    public record SinksProps(
            @DefaultValue SinkToggle billing,
            @DefaultValue SinkToggle audit,
            @DefaultValue SinkToggle analytics
    ) {}

    /** Per-sink feature flag — allows disabling individual sinks without redeployment. */
    public record SinkToggle(
            @DefaultValue("true") boolean enabled
    ) {}
}
