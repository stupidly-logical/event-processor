package com.eventprocessor.sdk;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot auto-configuration entry point for the SDK.
 *
 * <p>Explicitly registers the SDK's JPA entity and repository with Spring Data
 * so that consumer-service and producer-service — whose {@code @SpringBootApplication}
 * package roots don't include {@code com.eventprocessor.sdk} — still pick up
 * the DLQ infrastructure automatically via the auto-config import chain.
 *
 * <p>{@code @EnableScheduling} activates the {@link com.eventprocessor.sdk.dlq.DlqReplayer}.
 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackages = "com.eventprocessor.sdk")
@EnableJpaRepositories(basePackages = "com.eventprocessor.sdk.dlq")
@EntityScan(basePackages = "com.eventprocessor.sdk.model")
public class SdkAutoConfiguration {
}
