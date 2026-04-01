package com.example.eventsim;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GithubArchiveSamples {
    private static final String[] EVENT_TYPES = {
            "PushEvent", "PushEvent", "PushEvent", "PullRequestEvent",
            "IssuesEvent", "WatchEvent", "PushEvent", "PullRequestEvent",
            "IssuesEvent", "WatchEvent"
    };
    private static final String[] ACTORS = {
            "octocat", "defunkt", "mojombo", "pjhyett", "wycats",
            "dhh", "gaearon", "torvalds", "yyx990803", "sindresorhus",
            "kentcdodds", "addyosmani", "JakeWharton", "tj", "isaacs",
            "fat", "substack", "hashicorp-bot", "renovate[bot]", "dependabot[bot]"
    };
    private static final String[] REPOS = {
            "rails/rails", "kubernetes/kubernetes", "spring-projects/spring-boot",
            "openai/openai-java", "nodejs/node", "vercel/next.js",
            "facebook/react", "microsoft/vscode", "apache/kafka",
            "golang/go", "rust-lang/rust", "elastic/elasticsearch",
            "hashicorp/terraform", "docker/compose", "redis/redis",
            "grafana/grafana", "prometheus/prometheus", "google/guava",
            "square/okhttp", "netflix/conductor"
    };
    private static final String[] BRANCHES = {
            "refs/heads/main", "refs/heads/master", "refs/heads/release/1.0",
            "refs/heads/feature/circuit-breaker", "refs/heads/hotfix/retry-policy"
    };
    private static final String[] ISSUE_ACTIONS = {"opened", "closed", "reopened"};
    private static final String[] PR_ACTIONS = {"opened", "closed", "synchronize"};
    private static final String[] ISSUE_TITLES = {
            "Retry policy does not respect jitter",
            "Subscriber lag spikes during duplicate bursts",
            "Improve dead letter replay visibility",
            "Analytics sink counts diverge on replay",
            "Circuit breaker transitions not exported"
    };
    private static final String[] PR_TITLES = {
            "Add idempotency cache metrics",
            "Tune half-open probe handling",
            "Introduce DLQ replay scheduler",
            "Fix duplicate accounting in pubsub",
            "Refine downstream sink fanout"
    };

    private GithubArchiveSamples() {
    }

    public static List<GithubEvent> load() {
        List<GithubEvent> events = new ArrayList<>(200);
        Instant base = Instant.parse("2015-01-01T15:00:00Z");
        for (int index = 0; index < 200; index++) {
            String type = EVENT_TYPES[index % EVENT_TYPES.length];
            String actorLogin = ACTORS[index % ACTORS.length];
            String repoName = REPOS[(index * 3) % REPOS.length];
            Instant createdAt = base.plus(index * 17L, ChronoUnit.SECONDS);
            GithubEvent.Actor actor = new GithubEvent.Actor(
                    10_000L + index,
                    actorLogin,
                    actorLogin,
                    "https://api.github.com/users/" + actorLogin,
                    "https://avatars.githubusercontent.com/u/" + (10_000 + index)
            );
            GithubEvent.Repo repo = new GithubEvent.Repo(
                    200_000L + index,
                    repoName,
                    "https://api.github.com/repos/" + repoName
            );
            Map<String, Object> payload = switch (type) {
                case "PushEvent" -> pushPayload(index);
                case "PullRequestEvent" -> pullRequestPayload(index, repoName, actorLogin);
                case "IssuesEvent" -> issuePayload(index, repoName, actorLogin);
                case "WatchEvent" -> watchPayload(index);
                default -> Map.of();
            };
            events.add(new GithubEvent(
                    String.valueOf(9_000_000_000L + index),
                    type,
                    actor,
                    repo,
                    true,
                    createdAt.toString(),
                    payload
            ));
        }
        return List.copyOf(events);
    }

    private static Map<String, Object> pushPayload(int index) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ref", BRANCHES[index % BRANCHES.length]);
        payload.put("size", (index % 4) + 1);
        payload.put("distinct_size", (index % 3) + 1);
        payload.put("head", "deadbeef" + index);
        payload.put("before", "cafebabe" + index);
        payload.put("commits", List.of(
                Map.of("sha", "a" + index + "1", "message", "Refine retry logic"),
                Map.of("sha", "a" + index + "2", "message", "Add metrics wiring")
        ));
        return payload;
    }

    private static Map<String, Object> pullRequestPayload(int index, String repoName, String actorLogin) {
        Map<String, Object> pullRequest = new LinkedHashMap<>();
        pullRequest.put("number", 100 + index);
        pullRequest.put("state", index % 4 == 0 ? "closed" : "open");
        pullRequest.put("title", PR_TITLES[index % PR_TITLES.length]);
        pullRequest.put("html_url", "https://github.com/" + repoName + "/pull/" + (100 + index));
        pullRequest.put("user", Map.of("login", actorLogin));
        pullRequest.put("merged", index % 7 == 0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", PR_ACTIONS[index % PR_ACTIONS.length]);
        payload.put("number", 100 + index);
        payload.put("pull_request", pullRequest);
        return payload;
    }

    private static Map<String, Object> issuePayload(int index, String repoName, String actorLogin) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("number", 1_000 + index);
        issue.put("title", ISSUE_TITLES[index % ISSUE_TITLES.length]);
        issue.put("html_url", "https://github.com/" + repoName + "/issues/" + (1_000 + index));
        issue.put("state", index % 4 == 0 ? "closed" : "open");
        issue.put("user", Map.of("login", actorLogin));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", ISSUE_ACTIONS[index % ISSUE_ACTIONS.length]);
        payload.put("issue", issue);
        return payload;
    }

    private static Map<String, Object> watchPayload(int index) {
        return Map.of(
                "action", "started",
                "starred_at", Instant.parse("2015-01-01T15:00:00Z").plus(index * 11L, ChronoUnit.SECONDS).toString()
        );
    }
}
