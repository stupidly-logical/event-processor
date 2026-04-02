package com.eventprocessor.producer.generator;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubArchiveEventGeneratorTest {

    GitHubArchiveEventGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new GitHubArchiveEventGenerator();
    }

    // ── Core field presence ──────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(EventType.class)
    void generate_byType_populatesRequiredFields(EventType type) {
        GitHubEvent event = generator.generate(type);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getEventType()).isEqualTo(type);
        assertThat(event.getRepoId()).isPositive();
        assertThat(event.getRepoName()).contains("/");
        assertThat(event.getActorLogin()).isNotBlank();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getPayload()).isNotEmpty();
        assertThat(event.getSchemaVersion()).isEqualTo(1);
    }

    @Test
    void generate_producesUniqueEventIds() {
        long distinct = IntStream.range(0, 1000)
                .mapToObj(i -> generator.generate().getEventId())
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(1000);
    }

    // ── Payload fields by type ───────────────────────────────────────────────

    @Test
    void pushEvent_hasRefAndCommits() {
        GitHubEvent e = generator.generate(EventType.PUSH_EVENT);
        assertThat(e.getPayload()).containsKey("ref");
        assertThat(e.getPayload().get("ref")).startsWith("refs/heads/");
        assertThat(e.getPayload()).containsKey("commits");
        assertThat(Integer.parseInt(e.getPayload().get("commits"))).isBetween(1, 10);
        assertThat(e.getPayload()).containsKey("head");
        assertThat(e.getPayload().get("head")).hasSize(40);  // SHA
    }

    @Test
    void pullRequestEvent_hasActionAndNumber() {
        GitHubEvent e = generator.generate(EventType.PULL_REQUEST_EVENT);
        assertThat(e.getPayload()).containsKey("action");
        assertThat(e.getPayload()).containsKey("number");
        assertThat(e.getPayload()).containsKey("additions");
        assertThat(e.getPayload()).containsKey("deletions");
        assertThat(Integer.parseInt(e.getPayload().get("number"))).isPositive();
    }

    @Test
    void issuesEvent_hasLabelAndState() {
        GitHubEvent e = generator.generate(EventType.ISSUES_EVENT);
        assertThat(e.getPayload()).containsKeys("action", "number", "state", "label");
        assertThat(e.getPayload().get("state")).isIn("open", "closed");
    }

    @Test
    void watchEvent_hasStartedAction() {
        GitHubEvent e = generator.generate(EventType.WATCH_EVENT);
        assertThat(e.getPayload().get("action")).isEqualTo("started");
        assertThat(e.getPayload()).containsKey("stargazers");
    }

    // ── Distribution ─────────────────────────────────────────────────────────

    @Test
    void generate_randomDistribution_roughlyMatchesWeights() {
        Map<EventType, Integer> counts = new EnumMap<>(EventType.class);
        for (EventType t : EventType.values()) counts.put(t, 0);

        int n = 10_000;
        for (int i = 0; i < n; i++) {
            EventType t = generator.generate().getEventType();
            counts.merge(t, 1, Integer::sum);
        }

        // Allow ±10% tolerance around expected weights
        assertThat(counts.get(EventType.PUSH_EVENT))
                .isBetween((int)(n * 0.40), (int)(n * 0.60));    // expect 50%
        assertThat(counts.get(EventType.WATCH_EVENT))
                .isBetween((int)(n * 0.15), (int)(n * 0.35));    // expect 25%
        assertThat(counts.get(EventType.PULL_REQUEST_EVENT))
                .isBetween((int)(n * 0.05), (int)(n * 0.25));    // expect 15%
        assertThat(counts.get(EventType.ISSUES_EVENT))
                .isBetween((int)(n * 0.02), (int)(n * 0.18));    // expect 10%
    }
}
