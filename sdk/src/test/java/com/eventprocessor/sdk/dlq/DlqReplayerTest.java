package com.eventprocessor.sdk.dlq;

import com.eventprocessor.sdk.cb.CircuitBreakerConfiguration;
import com.eventprocessor.sdk.model.DlqEvent;
import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqReplayerTest {

    @Mock DlqEventRepository repository;
    @Mock KafkaTemplate<String, byte[]> kafkaTemplate;
    @Mock StringRedisTemplate redisTemplate;

    DlqReplayer replayer;
    CircuitBreakerRegistry cbRegistry;
    DlqEvent dlqEvent;

    @BeforeEach
    void setUp() throws Exception {
        cbRegistry = new CircuitBreakerConfiguration().circuitBreakerRegistry();
        replayer = new DlqReplayer(repository, kafkaTemplate, redisTemplate,
                cbRegistry, new SimpleMeterRegistry());

        GitHubEvent event = GitHubEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(EventType.PUSH_EVENT)
                .setRepoId(1L)
                .setRepoName("test/repo")
                .setActorLogin("user")
                .setOccurredAt(Instant.now())
                .setPayload(Map.of())
                .setTraceId(null)
                .setSchemaVersion(1)
                .build();

        dlqEvent = new DlqEvent(
                event.getEventId(), "PUSH_EVENT", "github-events",
                serialize(event), null, "initial failure",
                Instant.now());
    }

    @Test
    void replay_closedCb_processesOnePendingEvent() {
        when(repository.findOldestPending(DlqReplayer.BATCH_SIZE)).thenReturn(List.of(dlqEvent));
        when(repository.save(any())).thenReturn(dlqEvent);
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class))).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class)));

        replayer.replay();

        assertThat(dlqEvent.getStatus()).isEqualTo(DlqEvent.Status.REPLAYED);
        verify(kafkaTemplate).send(anyString(), anyString(), any(byte[].class));
    }

    @Test
    void replay_openCb_skipsProcessing() {
        // Trip the CB by recording enough failures
        var cb = cbRegistry.circuitBreaker("kafka-publish");
        for (int i = 0; i < 20; i++) cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException());

        replayer.replay();

        verify(repository, never()).findOldestPending(anyInt());
    }

    @Test
    void replay_kafkaFails_incrementsRetry() {
        when(repository.findOldestPending(DlqReplayer.BATCH_SIZE)).thenReturn(List.of(dlqEvent));
        when(repository.save(any())).thenReturn(dlqEvent);
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class))).thenThrow(new RuntimeException("broker down"));

        replayer.replay();

        assertThat(dlqEvent.getRetryCount()).isEqualTo(1);
        assertThat(dlqEvent.getStatus()).isEqualTo(DlqEvent.Status.PENDING);
    }

    @Test
    void replay_maxRetriesExceeded_promotesToDead() {
        // Exhaust retries
        for (int i = 0; i < DlqReplayer.MAX_RETRIES; i++) {
            dlqEvent.incrementRetry("fail " + i);
        }
        when(repository.findOldestPending(DlqReplayer.BATCH_SIZE)).thenReturn(List.of(dlqEvent));
        when(repository.save(any())).thenReturn(dlqEvent);
        when(kafkaTemplate.send(anyString(), anyString(), any(byte[].class))).thenThrow(new RuntimeException("still down"));

        replayer.replay();

        assertThat(dlqEvent.getStatus()).isEqualTo(DlqEvent.Status.DEAD);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private byte[] serialize(GitHubEvent event) throws IOException {
        var writer = new SpecificDatumWriter<>(GitHubEvent.class);
        try (var out = new ByteArrayOutputStream()) {
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(event, encoder);
            encoder.flush();
            return out.toByteArray();
        }
    }
}
