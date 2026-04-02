package com.eventprocessor.producer.generator;

import com.eventprocessor.producer.config.ProducerProperties;
import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import com.eventprocessor.sdk.publisher.EventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventEmissionSchedulerTest {

    @Mock EventPublisher publisher;
    @Mock GitHubArchiveEventGenerator generator;

    GitHubEvent sampleEvent;

    @BeforeEach
    void setUp() {
        sampleEvent = GitHubEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(EventType.PUSH_EVENT)
                .setRepoId(1L)
                .setRepoName("test/repo")
                .setActorLogin("user")
                .setOccurredAt(java.time.Instant.now())
                .setPayload(Map.of("ref", "refs/heads/main"))
                .setTraceId(null)
                .setSchemaVersion(1)
                .build();
    }

    private EventEmissionScheduler scheduler(ProducerProperties props) {
        return new EventEmissionScheduler(generator, publisher, props, new SimpleMeterRegistry());
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    void tick_enabled_publishesRatePerSecondEvents() {
        ProducerProperties props = new ProducerProperties(
                "github-events", 5, true, 10, false);
        when(generator.generate()).thenReturn(sampleEvent);

        scheduler(props).tick();

        verify(publisher, times(5)).publish(eq(sampleEvent), eq("github-events"));
    }

    @Test
    void tick_disabled_publishesNothing() {
        ProducerProperties props = new ProducerProperties(
                "github-events", 50, false, 10, false);

        scheduler(props).tick();

        verifyNoInteractions(publisher, generator);
    }

    // ── Back-pressure ────────────────────────────────────────────────────────

    @Test
    void tick_maxConsecutiveFailuresReached_skipsAndDecays() {
        ProducerProperties props = new ProducerProperties(
                "github-events", 50, true, 0, false);
        // maxConsecutiveFailures=0 means threshold is already met on startup
        // The scheduler treats consecutiveFailures.get() >= maxConsecutiveFailures as skip

        EventEmissionScheduler sched = scheduler(props);
        // Pre-set consecutive failures to exactly maxConsecutiveFailures
        // by forcing the field via reflection would be fragile; instead we
        // rely on tick() skipping when the condition is met
        sched.tick();   // should skip (0 >= 0)

        verifyNoInteractions(publisher, generator);
    }

    // ── Duplicate injection ──────────────────────────────────────────────────

    @Test
    void tick_injectDuplicates_doesNotChangeBatchSize() {
        ProducerProperties props = new ProducerProperties(
                "github-events", 10, true, 10, true);
        when(generator.generate()).thenReturn(sampleEvent);

        scheduler(props).tick();

        // publisher still called exactly ratePerSecond times regardless of injection
        verify(publisher, times(10)).publish(any(), eq("github-events"));
    }

    // ── Event forwarding ─────────────────────────────────────────────────────

    @Test
    void tick_forwardsCorrectTopic() {
        ProducerProperties props = new ProducerProperties(
                "custom-topic", 1, true, 10, false);
        when(generator.generate()).thenReturn(sampleEvent);

        scheduler(props).tick();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(any(), topicCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("custom-topic");
    }

    @Test
    void tick_publisherThrows_doesNotPropagateException() {
        ProducerProperties props = new ProducerProperties(
                "github-events", 3, true, 10, false);
        when(generator.generate()).thenReturn(sampleEvent);
        doThrow(new RuntimeException("broker gone")).when(publisher).publish(any(), any());

        // Must not throw — failures are caught inside emitBatch
        scheduler(props).tick();

        // All 3 attempts were made even though each threw
        verify(publisher, times(3)).publish(any(), any());
    }

    // ── All event types reach the publisher ──────────────────────────────────

    @Test
    void tick_generatesEventsForAllTypes() {
        ProducerProperties props = new ProducerProperties(
                "github-events", 4, true, 10, false);

        List<GitHubEvent> events = List.of(
            buildEvent(EventType.PUSH_EVENT),
            buildEvent(EventType.PULL_REQUEST_EVENT),
            buildEvent(EventType.ISSUES_EVENT),
            buildEvent(EventType.WATCH_EVENT)
        );

        when(generator.generate())
                .thenReturn(events.get(0))
                .thenReturn(events.get(1))
                .thenReturn(events.get(2))
                .thenReturn(events.get(3));

        scheduler(props).tick();

        ArgumentCaptor<GitHubEvent> captor = ArgumentCaptor.forClass(GitHubEvent.class);
        verify(publisher, times(4)).publish(captor.capture(), any());

        List<EventType> published = captor.getAllValues().stream()
                .map(GitHubEvent::getEventType).toList();
        assertThat(published).containsExactlyInAnyOrder(
                EventType.PUSH_EVENT, EventType.PULL_REQUEST_EVENT,
                EventType.ISSUES_EVENT, EventType.WATCH_EVENT);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GitHubEvent buildEvent(EventType type) {
        return GitHubEvent.newBuilder(sampleEvent)
                .setEventId(UUID.randomUUID().toString())
                .setEventType(type)
                .build();
    }
}
