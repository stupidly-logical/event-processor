package com.example.eventsim;

import java.util.Map;

public record GithubEvent(
        String archiveId,
        String type,
        Actor actor,
        Repo repo,
        boolean publicEvent,
        String createdAt,
        Map<String, Object> payload) {

    public record Actor(long id, String login, String displayLogin, String url, String avatarUrl) {
    }

    public record Repo(long id, String name, String url) {
    }
}
