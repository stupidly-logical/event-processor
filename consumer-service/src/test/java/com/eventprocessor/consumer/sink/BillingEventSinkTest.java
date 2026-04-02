package com.eventprocessor.consumer.sink;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingEventSinkTest {

    BillingEventSink sink;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        sink = new BillingEventSink(registry);
    }

    @Test
    void supports_pushAndPr_notOthers() {
        assertThat(sink.supports(EventType.PUSH_EVENT)).isTrue();
        assertThat(sink.supports(EventType.PULL_REQUEST_EVENT)).isTrue();
        assertThat(sink.supports(EventType.ISSUES_EVENT)).isFalse();
        assertThat(sink.supports(EventType.WATCH_EVENT)).isFalse();
    }

    @Test
    void processPush_incrementsPushAndCommitCounters() {
        GitHubEvent event = buildEvent(EventType.PUSH_EVENT,
                Map.of("ref", "refs/heads/main", "commits", "4"));
        sink.process(event);

        assertThat(registry.counter("ep.billing.push_billed").count()).isEqualTo(1.0);
        assertThat(registry.counter("ep.billing.commits_total").count()).isEqualTo(4.0);
    }

    @Test
    void processPush_badCommitCount_fallsBackToOne() {
        GitHubEvent event = buildEvent(EventType.PUSH_EVENT,
                Map.of("commits", "not-a-number"));
        sink.process(event);  // must not throw
        assertThat(registry.counter("ep.billing.commits_total").count()).isEqualTo(1.0);
    }

    @Test
    void processPr_merged_incrementsMergedCounter() {
        GitHubEvent event = buildEvent(EventType.PULL_REQUEST_EVENT,
                Map.of("merged", "true", "additions", "50", "deletions", "10", "number", "99"));
        sink.process(event);

        assertThat(registry.counter("ep.billing.pr_merged_billed").count()).isEqualTo(1.0);
    }

    @Test
    void processPr_notMerged_doesNotBill() {
        GitHubEvent event = buildEvent(EventType.PULL_REQUEST_EVENT,
                Map.of("merged", "false", "number", "100"));
        sink.process(event);

        assertThat(registry.counter("ep.billing.pr_merged_billed").count()).isEqualTo(0.0);
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
