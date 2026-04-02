package com.eventprocessor.sdk.dedup;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RedisDeduplicator}.
 * Uses a real Redis container via Testcontainers.
 */
@SpringBootTest(classes = DedupTestConfig.class)
@Testcontainers
class RedisDeduplicatorTest {

    @Container
    static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7.2-alpine"));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    RedisDeduplicator deduplicator;

    private String eventId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID().toString();
    }

    @Test
    void firstCall_returnsTrue() {
        assertThat(deduplicator.isNew(eventId)).isTrue();
    }

    @Test
    void secondCall_sameId_returnsFalse() {
        deduplicator.isNew(eventId);
        assertThat(deduplicator.isNew(eventId)).isFalse();
    }

    @Test
    void afterEvict_firstCallReturnsTrue() {
        deduplicator.isNew(eventId);
        deduplicator.evict(eventId);
        assertThat(deduplicator.isNew(eventId)).isTrue();
    }

    /**
     * 10 concurrent threads race to claim the same eventId.
     * Exactly 1 must win; the other 9 must see a duplicate.
     */
    @Test
    void concurrentCalls_exactlyOneWins() throws InterruptedException {
        int threads = 10;
        var latch = new CountDownLatch(threads);
        var wins = new AtomicInteger();

        try (var exec = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        if (deduplicator.isNew(eventId)) wins.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await();
        assertThat(wins.get()).isEqualTo(1);
    }
}
