package com.eventprocessor.producer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer infrastructure.
 *
 * <p>Declares the {@link KafkaTemplate} used by {@link com.eventprocessor.sdk.publisher.EventPublisher}
 * and the {@link NewTopic} beans that ensure the topic exists on startup
 * (Redpanda auto-creates, but explicit beans make intent clear and allow
 * partition/replica tuning without side-channel tooling).
 *
 * <p>Producer is configured for exactly-once semantics:
 * {@code enable.idempotence=true}, {@code acks=all}, {@code retries=3},
 * {@code max.in.flight.requests.per.connection=1}.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:19092}")
    private String bootstrapServers;

    // ── KafkaTemplate ────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, byte[]> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerProps());
    }

    @Bean
    public KafkaTemplate<String, byte[]> kafkaTemplate(ProducerFactory<String, byte[]> pf) {
        var template = new KafkaTemplate<>(pf);
        template.setObservationEnabled(true);   // wires Micrometer + OTel auto-instrumentation
        return template;
    }

    // ── Topic declarations ───────────────────────────────────────────────────

    /** Primary event topic — 3 partitions (parallelism), RF=1 in local dev. */
    @Bean
    public NewTopic githubEventsTopic() {
        return TopicBuilder.name("github-events")
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** DLQ replay topic — single partition to guarantee FIFO ordering. */
    @Bean
    public NewTopic githubEventsDlqTopic() {
        return TopicBuilder.name("github-events.dlq")
                .partitions(1)
                .replicas(1)
                .build();
    }

    // ── Producer properties ──────────────────────────────────────────────────

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

        // Exactly-once / idempotent producer
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);

        // Timeouts
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);          // micro-batching
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16_384);    // 16 KB batch
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        // Client ID shows up in Redpanda / Prometheus logs
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "producer-service");

        return props;
    }
}
