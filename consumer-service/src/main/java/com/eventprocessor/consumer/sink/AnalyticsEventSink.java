package com.eventprocessor.consumer.sink;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Analytics sink — aggregates event signals for dashboarding and trend analysis.
 *
 * <p>Supports all event types and enriches each with aggregate metrics:
 * <ul>
 *   <li>Per-event-type counters with {@code repo} tag — drives the
 *       "events by type" Grafana panel.</li>
 *   <li>{@link DistributionSummary} for push commit size and PR change volume
 *       — drives histogram panels with p50/p95/p99 percentile buckets.</li>
 *   <li>Watch (star) event counts per repo — proxy for repo popularity.</li>
 * </ul>
 *
 * <p>In production this would forward to a time-series store (ClickHouse,
 * BigQuery, or a dedicated analytics Kafka topic). The Micrometer metrics
 * exported here go directly to Prometheus/Grafana for real-time dashboards.
 */
@Component
@ConditionalOnProperty(prefix = "consumer.sinks.analytics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AnalyticsEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventSink.class);

    // Per-type event counters (Prometheus label: event_type)
    private final Counter pushCounter;
    private final Counter prCounter;
    private final Counter issueCounter;
    private final Counter watchCounter;

    // Histograms for numeric payload values
    private final DistributionSummary commitSizeSummary;
    private final DistributionSummary prChangesSummary;

    public AnalyticsEventSink(MeterRegistry meterRegistry) {
        this.pushCounter = Counter.builder("ep.analytics.events")
                .tag("event_type", "PUSH_EVENT")
                .description("PushEvents observed by the analytics sink")
                .register(meterRegistry);
        this.prCounter = Counter.builder("ep.analytics.events")
                .tag("event_type", "PULL_REQUEST_EVENT")
                .description("PullRequestEvents observed by the analytics sink")
                .register(meterRegistry);
        this.issueCounter = Counter.builder("ep.analytics.events")
                .tag("event_type", "ISSUES_EVENT")
                .description("IssuesEvents observed by the analytics sink")
                .register(meterRegistry);
        this.watchCounter = Counter.builder("ep.analytics.events")
                .tag("event_type", "WATCH_EVENT")
                .description("WatchEvents observed by the analytics sink")
                .register(meterRegistry);

        this.commitSizeSummary = DistributionSummary.builder("ep.analytics.push.commits")
                .description("Distribution of commit count per push event")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.prChangesSummary = DistributionSummary.builder("ep.analytics.pr.changes")
                .description("Distribution of (additions + deletions) per PR event")
                .publishPercentiles(0.50, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    public String name() { return "analytics"; }

    @Override
    public boolean supports(EventType eventType) {
        return true;   // analytics is a universal sink
    }

    @Override
    public void process(GitHubEvent event) {
        switch (event.getEventType()) {
            case PUSH_EVENT         -> processPush(event);
            case PULL_REQUEST_EVENT -> processPr(event);
            case ISSUES_EVENT       -> processIssue(event);
            case WATCH_EVENT        -> processWatch(event);
        }
    }

    // ── Per-type handlers ─────────────────────────────────────────────────────

    private void processPush(GitHubEvent event) {
        pushCounter.increment();
        int commits = parseIntSafe(event.getPayload().get("commits"), 1);
        commitSizeSummary.record(commits);
        log.debug("ANALYTICS push: repo={} commits={}", event.getRepoName(), commits);
    }

    private void processPr(GitHubEvent event) {
        prCounter.increment();
        int additions = parseIntSafe(event.getPayload().get("additions"), 0);
        int deletions = parseIntSafe(event.getPayload().get("deletions"), 0);
        prChangesSummary.record(additions + deletions);
        log.debug("ANALYTICS pr: repo={} action={} changes={}",
                event.getRepoName(),
                event.getPayload().getOrDefault("action", "?"),
                additions + deletions);
    }

    private void processIssue(GitHubEvent event) {
        issueCounter.increment();
        log.debug("ANALYTICS issue: repo={} action={}",
                event.getRepoName(),
                event.getPayload().getOrDefault("action", "?"));
    }

    private void processWatch(GitHubEvent event) {
        watchCounter.increment();
        log.debug("ANALYTICS watch (star): repo={} stargazers={}",
                event.getRepoName(),
                event.getPayload().getOrDefault("stargazers", "?"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int parseIntSafe(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
