package com.eventprocessor.sdk.dedup;

import com.eventprocessor.sdk.SdkAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal Spring context for dedup tests — Redis only, no DB/Kafka/Flyway.
 */
@Configuration
@EnableAutoConfiguration(exclude = {
        SdkAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
public class DedupTestConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    RedisDeduplicator redisDeduplicator(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        return new RedisDeduplicator(redisTemplate, meterRegistry);
    }
}
