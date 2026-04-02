package com.eventprocessor.sdk.publisher;

import com.eventprocessor.sdk.cb.CircuitBreakerConfiguration;
import com.eventprocessor.sdk.dedup.RedisDeduplicator;
import com.eventprocessor.sdk.dlq.DlqRouter;
import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.messaging.Message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock KafkaTemplate<String, byte[]> kafkaTemplate;
    @Mock RedisDeduplicator deduplicator;
    @Mock DlqRouter dlqRouter;

    EventPublisher publisher;
    GitHubEvent event;

    @BeforeEach
    void setUp() {
        CircuitBreakerRegistry registry = new CircuitBreakerConfiguration().circuitBreakerRegistry();
        publisher = new EventPublisher(kafkaTemplate, deduplicator, dlqRouter, registry,
                new SimpleMeterRegistry());

        event = GitHubEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(EventType.PUSH_EVENT)
                .setRepoId(12345L)
                .setRepoName("octocat/Hello-World")
                .setActorLogin("octocat")
                .setOccurredAt(java.time.Instant.now())
                .setPayload(Map.of("ref", "refs/heads/main", "commits", "3"))
                .setTraceId(null)
                .setSchemaVersion(1)
                .build();
    }

    @Test
    void publish_newEvent_sendsToKafka() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(
                CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(event, "github-events");

        verify(kafkaTemplate).send(any(Message.class));
        verify(dlqRouter, never()).route(any(), any(), any());
    }

    @Test
    void publish_duplicate_droppedSilently() {
        when(deduplicator.isNew(any())).thenReturn(false);

        publisher.publish(event, "github-events");

        verify(kafkaTemplate, never()).send(any(Message.class));
        verify(dlqRouter, never()).route(any(), any(), any());
    }

    @Test
    void publish_kafkaFailure_routesToDlq() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(kafkaTemplate.send(any(Message.class))).thenThrow(new RuntimeException("broker unavailable"));

        publisher.publish(event, "github-events");

        verify(dlqRouter).route(eq(event), eq("github-events"), any());
    }
}
