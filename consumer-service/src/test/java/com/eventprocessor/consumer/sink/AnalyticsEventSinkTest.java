package com.eventprocessor.consumer.sink;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsEventSinkTest {

    AnalyticsEventSink sink;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sink = new AnalyticsEventSink(registry);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void supports_allEventTypes(EventType type) {
        assertThat(sink.supports(type)).isTrue();
    }

    @Test
    void processPush_recordsCommitSizeHistogram() {
        sink.process(buildEvent(EventType.PUSH_EVENT, Map.of("commits", "7")));

        assertThat(registry.counter("ep.analytics.events", "event_type", "PUSH_EVENT").count())
                .isEqualTo(1.0);
        var summary = registry.summary("ep.analytics.push.commits");
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(7.0);
    }

    @Test
    void processPr_recordsChangesSummary() {
        sink.process(buildEvent(EventType.PULL_REQUEST_EVENT,
                Map.of("additions", "30", "deletions", "10", "action", "opened")));

        assertThat(registry.counter("ep.analytics.events", "event_type", "PULL_REQUEST_EVENT").count())
                .isEqualTo(1.0);
        var summary = registry.summary("ep.analytics.pr.changes");
        assertThat(summary.totalAmount()).isEqualTo(40.0);  // 30 + 10
    }

    @Test
    void processWatch_incrementsWatchCounter() {
        sink.process(buildEvent(EventType.WATCH_EVENT, Map.of("action", "started")));

        assertThat(registry.counter("ep.analytics.events", "event_type", "WATCH_EVENT").count())
                .isEqualTo(1.0);
    }

    @Test
    void processIssue_incrementsIssueCounter() {
        sink.process(buildEvent(EventType.ISSUES_EVENT, Map.of("action", "opened")));

        assertThat(registry.counter("ep.analytics.events", "event_type", "ISSUES_EVENT").count())
                .isEqualTo(1.0);
    }

    @Test
    void processPush_missingCommitKey_doesNotThrow() {
        // empty payload — fallback must handle missing key
        sink.process(buildEvent(EventType.PUSH_EVENT, Map.of()));
        assertThat(registry.summary("ep.analytics.push.commits").totalAmount()).isEqualTo(1.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GitHubEvent buildEvent(EventType type, Map<String, String> payload) {
        return GitHubEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(type)
                .setRepoId(1L)
                .setRepoName("test/repo")
                .setActorLogin("user")
                .setOccurredAt(java.time.Instant.now())
                .setPayload(payload)
                .setTraceId(null)
                .setSchemaVersion(1)
                .build();
    }
}
