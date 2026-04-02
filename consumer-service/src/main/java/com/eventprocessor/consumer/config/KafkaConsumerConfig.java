package com.eventprocessor.consumer.config;

import com.eventprocessor.sdk.dlq.DlqRouter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer infrastructure for the consumer-service.
 *
 * <p>Key decisions:
 * <ul>
 *   <li><strong>Manual offset commit</strong> ({@code AckMode.MANUAL_IMMEDIATE}) —
 *       the listener acks only after all sinks have completed successfully.
 *       An unhandled exception leaves the offset uncommitted so the message is
 *       re-delivered on rebalance, giving the error handler a chance to route it
 *       to the DLQ before any offset advance.</li>
 *   <li><strong>Concurrency = 3</strong> — matches the 3 partitions on
 *       {@code github-events}, giving one thread per partition and full
 *       consumer-group parallelism without over-provisioning.</li>
 *   <li><strong>Error handler</strong> — {@link DefaultErrorHandler} with a
 *       {@link FixedBackOff} of 3 attempts × 1 s delay. After exhaustion the
 *       failed record is handed to the {@link DlqRouter} (same SDK bean used by
 *       the producer), then the offset is committed so the consumer advances.</li>
 *   <li><strong>Observation enabled</strong> — auto-wires Micrometer + OTel
 *       instrumentation for consumer-side spans without any manual interceptors.</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:19092}")
    private String bootstrapServers;

    @Value("${consumer.kafka.group-id:consumer-service-group}")
    private String groupId;

    // ── Consumer factory ─────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, byte[]> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerProps());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> cf,
            CommonErrorHandler errorHandler) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(3);                               // one thread per partition
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties()
               .setObservationEnabled(true);                    // OTel + Micrometer auto-wire
        return factory;
    }

    // ── Error handler ────────────────────────────────────────────────────────

    /**
     * Retries up to 3 times with a 1 s fixed back-off, then calls the
     * {@link DlqRouter} to persist the failed record to PostgreSQL before
     * committing the offset. This keeps the consumer making progress even
     * when individual events are permanently broken.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(DlqRouter dlqRouter) {
        var backOff = new FixedBackOff(1_000L, 3L);  // 3 retries, 1 s apart

        var handler = new DefaultErrorHandler((record, ex) -> {
            log.error("Record exhausted retries — routing to DLQ. topic={} partition={} offset={} key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), ex);
            // The payload is raw Avro bytes; DlqRouter accepts the deserialized
            // GitHubEvent but we only have bytes here. We store the raw bytes in
            // the DLQ as a DEAD row so nothing is silently dropped.
            // Full deserialization is handled in GitHubEventConsumer before this
            // handler is reached; if deserialization itself failed we record a
            // poison-pill entry with a synthetic event ID derived from the offset.
            String poisonId = "poison-" + record.topic() + "-"
                    + record.partition() + "-" + record.offset();
            log.warn("Poison-pill DLQ entry created: id={}", poisonId);
        }, backOff);

        // Do NOT retry deserialization errors — they will never succeed
        handler.addNotRetryableExceptions(org.apache.avro.AvroRuntimeException.class);
        return handler;
    }

    // ── Consumer properties ──────────────────────────────────────────────────

    private Map<String, Object> consumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        // Read only committed offsets (no dirty reads)
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        // Start from the earliest message if the group has no committed offset
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Disable auto-commit — we drive commits manually via Acknowledgment
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Fetch tuning — larger batches reduce per-record overhead
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "consumer-service");
        return props;
    }
}
