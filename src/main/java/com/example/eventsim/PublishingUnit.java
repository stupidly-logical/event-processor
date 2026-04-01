package com.example.eventsim;

public record PublishingUnit(
        String idempotencyKey,
        GithubEvent event,
        long createdSimulatedAtMs) {
}
