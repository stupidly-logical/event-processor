package com.eventprocessor.producer.generator;

import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates statistically realistic GitHub Archive events for all four
 * supported event types.
 *
 * <p>Payload fidelity mirrors the real GitHub Archive v3 JSON schema but is
 * flattened to {@code Map<String, String>} to fit the Avro schema.
 * Key distributions are weighted to reflect real-world traffic:
 * PushEvent ≈ 50%, WatchEvent ≈ 25%, PullRequestEvent ≈ 15%, IssuesEvent ≈ 10%.
 */
@Component
public class GitHubArchiveEventGenerator {

    // ── Static data pools ────────────────────────────────────────────────────

    private static final List<long[]> REPOS = List.of(
        new long[]{1296269L},   // octocat/Hello-World
        new long[]{44838949L},  // spring-projects/spring-boot
        new long[]{10270250L},  // facebook/react
        new long[]{28457823L},  // microsoft/vscode
        new long[]{6093790L},   // apache/kafka
        new long[]{41881900L},  // redis/redis
        new long[]{13491895L},  // kubernetes/kubernetes
        new long[]{15062869L},  // golang/go
        new long[]{7508411L},   // torvalds/linux
        new long[]{3742902L}    // docker/docker
    );

    private static final List<String[]> REPO_META = List.of(
        new String[]{"octocat/Hello-World",          "octocat"},
        new String[]{"spring-projects/spring-boot",  "bclozel"},
        new String[]{"facebook/react",               "gaearon"},
        new String[]{"microsoft/vscode",             "bpasero"},
        new String[]{"apache/kafka",                 "guozhang"},
        new String[]{"redis/redis",                  "antirez"},
        new String[]{"kubernetes/kubernetes",        "thockin"},
        new String[]{"golang/go",                    "bradfitz"},
        new String[]{"torvalds/linux",               "torvalds"},
        new String[]{"docker/docker",                "crosbymichael"}
    );

    private static final List<String> BRANCHES = List.of(
        "main", "master", "develop", "release/2.x", "feature/perf-improvements",
        "fix/null-pointer", "chore/bump-deps", "hotfix/cve-2024-1234"
    );

    private static final List<String> LANGUAGES = List.of(
        "Java", "TypeScript", "Python", "Go", "Rust", "C++", "JavaScript", "Kotlin"
    );

    private static final List<String> PR_ACTIONS = List.of(
        "opened", "closed", "reopened", "synchronize", "review_requested"
    );

    private static final List<String> ISSUE_ACTIONS = List.of(
        "opened", "closed", "reopened", "labeled", "assigned", "unassigned"
    );

    private static final List<String> ISSUE_LABELS = List.of(
        "bug", "enhancement", "documentation", "good first issue",
        "help wanted", "performance", "security", "breaking-change"
    );

    private static final List<String> COMMIT_MESSAGES = List.of(
        "fix: resolve NPE in event deserialization",
        "feat: add Redis SET NX dedup support",
        "refactor: extract CircuitBreaker config",
        "docs: update README with architecture diagram",
        "test: add integration tests for DlqReplayer",
        "chore: bump Spring Boot to 3.2.5",
        "perf: batch Redis pipeline calls",
        "fix: handle Kafka send timeout correctly",
        "feat: add Prometheus histogram for publish latency",
        "ci: add testcontainers to CI pipeline"
    );

    // ── Weighted type distribution ───────────────────────────────────────────
    // PUSH=50%, WATCH=25%, PR=15%, ISSUES=10%

    private static final EventType[] TYPE_POOL;
    static {
        TYPE_POOL = new EventType[100];
        int i = 0;
        for (; i < 50; i++) TYPE_POOL[i] = EventType.PUSH_EVENT;
        for (; i < 75; i++) TYPE_POOL[i] = EventType.WATCH_EVENT;
        for (; i < 90; i++) TYPE_POOL[i] = EventType.PULL_REQUEST_EVENT;
        for (; i < 100; i++) TYPE_POOL[i] = EventType.ISSUES_EVENT;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Generates a single {@link GitHubEvent} with a randomly chosen type
     * according to the weighted distribution above.
     */
    public GitHubEvent generate() {
        Random rng = ThreadLocalRandom.current();
        EventType type = TYPE_POOL[rng.nextInt(100)];
        return generate(type);
    }

    /**
     * Generates a {@link GitHubEvent} of a specific type.
     */
    public GitHubEvent generate(EventType type) {
        Random rng = ThreadLocalRandom.current();
        int repoIdx = rng.nextInt(REPO_META.size());
        String[] meta = REPO_META.get(repoIdx);
        long repoId = REPOS.get(repoIdx)[0];

        return GitHubEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(type)
                .setRepoId(repoId)
                .setRepoName(meta[0])
                .setActorLogin(meta[1])
                .setOccurredAt(Instant.now())
                .setPayload(buildPayload(type, meta[0], rng))
                .setTraceId(null)       // filled in by EventPublisher from OTel context
                .setSchemaVersion(1)
                .build();
    }

    // ── Payload builders ─────────────────────────────────────────────────────

    private Map<String, String> buildPayload(EventType type, String repoName, Random rng) {
        return switch (type) {
            case PUSH_EVENT         -> buildPushPayload(repoName, rng);
            case PULL_REQUEST_EVENT -> buildPullRequestPayload(repoName, rng);
            case ISSUES_EVENT       -> buildIssuesPayload(repoName, rng);
            case WATCH_EVENT        -> buildWatchPayload(repoName, rng);
        };
    }

    /**
     * PushEvent — ref, before/after SHAs, commit count, language.
     */
    private Map<String, String> buildPushPayload(String repoName, Random rng) {
        int commitCount = rng.nextInt(10) + 1;
        Map<String, String> p = new HashMap<>();
        p.put("ref",           "refs/heads/" + pick(BRANCHES, rng));
        p.put("before",        randomSha(rng));
        p.put("head",          randomSha(rng));
        p.put("commits",       String.valueOf(commitCount));
        p.put("commit_msg",    pick(COMMIT_MESSAGES, rng));
        p.put("language",      pick(LANGUAGES, rng));
        p.put("forced",        String.valueOf(rng.nextInt(10) == 0));   // 10% forced pushes
        p.put("distinct_size", String.valueOf(commitCount));
        p.put("repo_full_name", repoName);
        return p;
    }

    /**
     * PullRequestEvent — action, number, merge status, additions/deletions.
     */
    private Map<String, String> buildPullRequestPayload(String repoName, Random rng) {
        int number  = rng.nextInt(10_000) + 1;
        int adds    = rng.nextInt(500);
        int deletes = rng.nextInt(200);
        Map<String, String> p = new HashMap<>();
        p.put("action",        pick(PR_ACTIONS, rng));
        p.put("number",        String.valueOf(number));
        p.put("title",         "PR #" + number + ": " + pick(COMMIT_MESSAGES, rng));
        p.put("state",         rng.nextBoolean() ? "open" : "closed");
        p.put("merged",        String.valueOf(rng.nextInt(3) == 0));    // ~33% merged
        p.put("additions",     String.valueOf(adds));
        p.put("deletions",     String.valueOf(deletes));
        p.put("changed_files", String.valueOf(rng.nextInt(20) + 1));
        p.put("base_ref",      pick(BRANCHES, rng));
        p.put("head_ref",      "feature/" + UUID.randomUUID().toString().substring(0, 8));
        p.put("repo_full_name", repoName);
        return p;
    }

    /**
     * IssuesEvent — action, number, title, labels.
     */
    private Map<String, String> buildIssuesPayload(String repoName, Random rng) {
        int number = rng.nextInt(5_000) + 1;
        Map<String, String> p = new HashMap<>();
        p.put("action",        pick(ISSUE_ACTIONS, rng));
        p.put("number",        String.valueOf(number));
        p.put("title",         "Issue #" + number + ": " + pick(COMMIT_MESSAGES, rng));
        p.put("state",         rng.nextBoolean() ? "open" : "closed");
        p.put("label",         pick(ISSUE_LABELS, rng));
        p.put("comments",      String.valueOf(rng.nextInt(50)));
        p.put("repo_full_name", repoName);
        return p;
    }

    /**
     * WatchEvent — always action=started (GitHub API only emits stars).
     */
    private Map<String, String> buildWatchPayload(String repoName, Random rng) {
        Map<String, String> p = new HashMap<>();
        p.put("action",        "started");
        p.put("repo_full_name", repoName);
        p.put("stargazers",    String.valueOf(rng.nextInt(100_000) + 100));
        return p;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> T pick(List<T> list, Random rng) {
        return list.get(rng.nextInt(list.size()));
    }

    /** Generates a fake 40-char hex SHA-1. */
    private static String randomSha(Random rng) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] sha = new char[40];
        for (int i = 0; i < 40; i++) sha[i] = hex[rng.nextInt(16)];
        return new String(sha);
    }
}
