# Contributing Guide

Welcome! This guide will help you contribute to the Event Processor project.

## Code of Conduct

Be respectful, inclusive, and professional. We're building great infrastructure together.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork**: `git clone https:/github.com/YOUR_USERNAME/event-processor.git`
3. **Create a feature branch**: `git checkout -b feat/your-feature`
4. **Set up local environment**: See [DEVELOPMENT.md](DEVELOPMENT.md#quick-start-local-development)

## Development Workflow

### 1. Make Your Changes

```bash
# Start with a clean environment
docker-compose -f observability/docker-compose.yml up -d

# In another terminal, make your changes
# Edit code in your IDE
```

### 2. Write Tests

All contributions must include tests:

```bash
# Unit tests (fast, no containers)
mvn test -pl sdk

# Integration tests (with testcontainers)
mvn test -Pintegration

# Run everything
mvn clean verify
```

**Test coverage requirements:**
- New code: 80% coverage minimum
- Modified code: coverage should not decrease
- SDK module: 85% (critical path)

### 3. Code Quality

```bash
# Auto-format code
mvn spotless:apply

# Check formatting
mvn spotless:check

# Run linters
mvn checkstyle:check spotbugs:check

# All quality checks
mvn verify
```

### 4. Commit Message Format

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types:**
- `feat` — A new feature
- `fix` — A bug fix
- `docs` — Documentation
- `style` — Code style changes (no logic)
- `refactor` — Code refactoring
- `perf` — Performance improvements
- `test` — Adding/updating tests
- `chore` — Maintenance, dependencies, build

**Scopes:**
- `sdk` — SDK module
- `producer` — Producer service
- `consumer` — Consumer service
- `infra` — Terraform / infrastructure
- `ci` — GitHub Actions
- `docs` — Documentation

**Examples:**

```bash
# Good
git commit -m "feat(sdk): add circuit breaker retry with jitter"
git commit -m "fix(producer): handle Kafka connection timeout gracefully"
git commit -m "docs: add API contract for DLQ replay endpoint"
git commit -m "test(sdk): add deduplicator integration tests"

# Bad
git commit -m "updates"
git commit -m "Fixed stuff"
git commit -m "WIP: trying something"
```

### 5. Push and Create PR

```bash
git push origin feat/your-feature
```

**PR title:** Use the same format as commit messages
**PR description:** Explain what & why, link issues

```markdown
## Description
Adds retry logic with exponential backoff to the EventPublisher.

## Fixes
Closes #123

## Testing
- [x] Unit tests pass
- [x] Integration tests with Testcontainers
- [x] Manual smoke test with docker-compose

## Checklist
- [x] Code follows style guidelines
- [x] Self-review completed
- [x] Comments added for complex logic
- [x] Documentation updated (README, API.md)
- [x] No breaking changes
```

## Architecture Guidelines

### SDK Module

**Principles:**
- Zero external dependencies (springs, kafka, gcp) — only abstractions
- Well-documented dedup logic
- Clear Circuit Breaker configuration
- OpenTelemetry instrumentation for observability

**When adding features:**
1. Write tests first (TDD)
2. Add metrics via Micrometer
3. Document with Javadoc
4. Update README.md if you added a new concept

### Producer Service

**Principles:**
- Thin HTTP wrapper around SDK
- Stateless (easy horizontal scaling)
- Clear error responses
- Request logging + correlation ID tracking

**REST Endpoints:**
- Follow RESTful conventions
- Use 202 Accepted for async operations
- Return error details on 400/422/5xx
- Include Swagger/OpenAPI docs

### Consumer Service

**Principles:**
- Pure Pub/Sub consumer (no state)
- Idempotent message processing (same ID = same result)
- Clear subscription handling
- DLQ replay coordination

---

## Testing Standards

### Unit Tests

Located in `src/test/java`:
- Use JUnit 5 + Mockito
- Mock external dependencies
- Test edge cases (null, empty, boundary values)
- Use descriptive test names: `testXxxWhenYyyThenZzz()`

```java
@Test
void testPublishWhenDuplicateUuidThenDropped() {
    // Arrange
    String eventId = "test-123";
    mockRedisHasKey(eventId);

    // Act
    publisher.publish(event);

    // Assert
    verify(kafkaTemplate, never()).send(any());
}
```

### Integration Tests

Located in `src/test/java`, marked with `@Tag("integration")`:
- Use Testcontainers for external services
- Test real SDK + infrastructure flow
- Include latency assertions

```java
@SpringBootTest
@Tag("integration")
class EventPublisherIntegrationTest {
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("..."));

    @Test
    void publishEventEndToEnd() {
        // Full flow test with real Kafka
    }
}
```

### Performance Tests

For performance-critical paths:
```java
@Test
void publishThroughputBenchmark() {
    Instant start = Instant.now();
    for (int i = 0; i < 10_000; i++) {
        publisher.publish(event);
    }
    Duration elapsed = Duration.between(start, Instant.now());
    long throughput = 10_000 * 1000 / elapsed.toMillis();

    assertThat(throughput).isGreaterThan(5_000);  // 5k events/sec
}
```

---

## Documentation

### Code Comments

- Comment "why", not "what"
- For complex algorithms, add detailed comments
- Keep comments in sync with code

```java
// GOOD: Explains the why
// Redis SETNX returns nil if key exists, allowing us to detect duplicates
// before expensive Kafka serialization
if (!redisDedup.setIfAbsent(eventId, ttl)) {
    return;  // Duplicate detected
}

// BAD: Just restates code
// Increment counter
counter.increment();
```

### Javadoc

- Required for public classes and methods
- Include `@param`, `@return`, `@throws`
- Include use case examples for complex methods

```java
/**
 * Publishes an event to Kafka with idempotency, circuit breaker protection,
 * and automatic DLQ routing on failure.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Redis dedup check (Layer 1)</li>
 *   <li>Circuit breaker check</li>
 *   <li>Kafka publish with exponential backoff retry</li>
 *   <li>DLQ route on final failure</li>
 * </ol>
 *
 * @param event the GitHub event to publish
 * @param topic target Kafka topic
 * @throws IllegalArgumentException if event.id is null
 *
 * @see RedisDeduplicator
 * @see CircuitBreakerConfiguration
 */
public void publish(GitHubEvent event, String topic) {
    // ...
}
```

### README Updates

When you add a feature:
1. Update relevant section in README.md
2. Add API contract to API.md (if new endpoint)
3. Update architecture diagram (if major change)
4. Add monitoring dashboard metric (if new metric)

---

## Review Process

### What Happens in Review

1. **Automated checks run:**
   - CI pipeline (build, tests, quality)
   - Code coverage (must not decrease)
   - Security scan (dependency vulnerabilities)

2. **Code review:**
   - Architecture alignment
   - Performance implications
   - Test coverage
   - Documentation completeness

3. **Approval:**
   - At least 1 maintainer approval required
   - All conversations resolved
   - CI passing

### How to Respond to Feedback

- Address feedback in new commits (don't amend)
- Mark conversations as resolved when addressed
- Ask questions if feedback is unclear
- Thank reviewers!

Example feedback response:
```
✅ Good point — I added error handling for null repo in 3a9d2b1
✅ Added integration test for circuit breaker HALF_OPEN state in 5c2e1f4
🤔 Question: Should we retry on both IOException and TimeoutException?
```

---

## Release Process

### For Maintainers Only

Releasing a new version:

```bash
# Create release branch
git checkout -b release/v1.2.0

# Update version in pom.xml
# mvn versions:set -DnewVersion=1.2.0

# Update CHANGELOG.md

# Create PR, get approvals

# Merge to main

# Tag release
git tag v1.2.0
git push origin v1.2.0

# GitHub Actions automatically:
# 1. Runs full test suite
# 2. Builds and pushes Docker images
# 3. Publishes SDK to Maven Central
# 4. Deploys to production
# 5. Posts release notes
```

---

## Common Issues & Solutions

### "Maven tests fail locally but pass in CI"

Usually environment-specific:
```bash
# Check environment vars
env | grep -E "KAFKA|REDIS"

# Start services
docker-compose -f observability/docker-compose.yml up -d

# Run tests again
mvn clean test -Pintegration
```

### "CircuitBreaker timing issues in tests"

Mock the clock:
```java
@Test
void testCircuitBreakerRecovery() {
    // Use SimulatedClock instead of real time
    CircuitBreaker cb = CircuitBreaker.of("test",
        CircuitBreakerConfig.from(defaultConfig)
            .slowCallRateThreshold(50)
            .build());
}
```

### "Code coverage changed unexpectedly"

```bash
# Generate coverage report
mvn clean test jacoco:report

# Open report
open sdk/target/site/jacoco/index.html

# Check which lines are uncovered
```

---

## Communication

- **Issues:** Use GitHub Issues for bugs, features, questions
- **Discussions:** Use GitHub Discussions for architecture debates
- **Slack:** (if enabled) For quick questions
- **EDR:** Design decisions in ADR (Architecture Decision Records)

---

## Additional Resources

- **Architecture:** See README.md "Architecture" section
- **API Contracts:** API.md with request/response examples
- **Deployment:** DEVELOPMENT.md and infra/terraform/README.md
- **Performance:** README.md "Performance Benchmarks"

---

## Thank You!

Your contributions make Event Processor better. We appreciate:
- Bug reports with reproducible steps
- Feature requests with use cases
- Code submissions with tests
- Documentation improvements
- Performance reports and optimizations

Welcome aboard! 🚀

---

**Last Updated:** 2024-01-02
