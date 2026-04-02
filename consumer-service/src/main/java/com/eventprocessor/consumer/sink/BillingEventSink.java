package com.eventprocessor.consumer.sink;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Billing sink — processes events that contribute to a repository's usage tier.
 *
 * <p>Billing-relevant events:
 * <ul>
 *   <li>{@code PUSH_EVENT} — commit volume drives storage/bandwidth billing.</li>
 *   <li>{@code PULL_REQUEST_EVENT} — action=merged counts as a billable CI run.</li>
 * </ul>
 *
 * <p>In a real system this would call a billing gRPC service or write to a
 * partitioned billing ledger. Here it records structured metrics and logs
 * to demonstrate the fan-out pattern and give Prometheus/Grafana signals.
 */
@Component
@ConditionalOnProperty(prefix = "consumer.sinks.billing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BillingEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(BillingEventSink.class);

    private static final Set<EventType> SUPPORTED = Set.of(
            EventType.PUSH_EVENT,
            EventType.PULL_REQUEST_EVENT
    );

    private final Counter pushBilledCounter;
    private final Counter prMergedBilledCounter;
    private final Counter commitsTotalCounter;

    public BillingEventSink(MeterRegistry meterRegistry) {
        this.pushBilledCounter = Counter.builder("ep.billing.push_billed")
                .description("Push events processed by the billing sink")
                .register(meterRegistry);
        this.prMergedBilledCounter = Counter.builder("ep.billing.pr_merged_billed")
                .description("Merged PRs processed by the billing sink")
                .register(meterRegistry);
        this.commitsTotalCounter = Counter.builder("ep.billing.commits_total")
                .description("Total commit count across all billed push events")
                .register(meterRegistry);
    }

    @Override
    public String name() { return "billing"; }

    @Override
    public boolean supports(EventType eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void process(GitHubEvent event) {
        switch (event.getEventType()) {
            case PUSH_EVENT -> processPush(event);
            case PULL_REQUEST_EVENT -> processPullRequest(event);
            default -> { /* supports() guards against reaching here */ }
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void processPush(GitHubEvent event) {
        int commits = parseIntSafe(event.getPayload().get("commits"), 1);
        pushBilledCounter.increment();
        commitsTotalCounter.increment(commits);

        log.info("BILLING push: repo={} actor={} commits={} ref={}",
                event.getRepoName(),
                event.getActorLogin(),
                commits,
                event.getPayload().getOrDefault("ref", "unknown"));
    }

    private void processPullRequest(GitHubEvent event) {
        boolean merged = Boolean.parseBoolean(event.getPayload().getOrDefault("merged", "false"));
        if (!merged) { return; }   // only merged PRs incur a CI billing event

        int additions = parseIntSafe(event.getPayload().get("additions"), 0);
        int deletions = parseIntSafe(event.getPayload().get("deletions"), 0);
        prMergedBilledCounter.increment();

        log.info("BILLING pr_merged: repo={} actor={} pr={} +{}/−{}",
                event.getRepoName(),
                event.getActorLogin(),
                event.getPayload().getOrDefault("number", "?"),
                additions,
                deletions);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int parseIntSafe(String value, int fallback) {
        if (value == null) { return fallback; }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
