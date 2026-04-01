# Event Publishing Simulation

This repository contains a fully self-contained Java simulation of a production-style event publishing pipeline:

- Java SDK with `EventPublisher.Builder`
- Redis-like idempotency cache with TTL
- PubSub with failure injection and redelivery
- Circuit breaker with `CLOSED -> OPEN -> HALF_OPEN -> CLOSED`
- Dead letter queue with replay
- Downstream DataFlow dedup window
- Event aggregator with `Billing`, `Audit`, and `Analytics` sinks
- Prometheus scrape endpoint and a provisioned Grafana dashboard

The dataset is a hardcoded set of 200 GitHub Archive-like events modeled after the `2015-01-01-15.json.gz` schema shape. No external event source is fetched.

## Run the simulation

```bash
mvn compile exec:java
```

Because the circuit breaker now uses the real `resilience4j-circuitbreaker` dependency, launching with plain `java -cp target/classes ...` is no longer sufficient unless you also build the dependency classpath.

Endpoints:

- `http://localhost:8080/metrics`
- `http://localhost:8080/health`
- `http://localhost:8080/api/state`

The simulation runs continuously and cycles through these phases:

1. Happy path
2. PubSub outage
3. Duplicate burst during recovery
4. Steady state

This makes the metrics visually useful without manual fault injection.

## Run Prometheus and Grafana

Start the Java app first, then in another terminal:

```bash
docker compose up
```

The provided Prometheus target uses `host.docker.internal:8080`, which works on macOS and Windows. On Linux, change that target to your host gateway or run the Java app inside a container.

UI endpoints:

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Grafana credentials: `admin` / `admin`

## Architecture notes

- Every publish unit gets a UUID idempotency key.
- Redis dedup is checked before publish. Duplicate publish attempts are dropped there.
- PubSub can fail publishes and can redeliver a published message with configurable probability.
- The circuit breaker uses a count-based sliding window of 20 calls and opens at 50% failure rate.
- `CallNotPermittedException` routes directly to the DLQ.
- Exponential backoff uses base `2000ms`, multiplier `2`, jitter `±20%`, with max `5` retries.
- DataFlow dedup catches redeliveries that bypass publisher-side Redis dedup.
- DLQ replay starts automatically when the circuit breaker is back in `CLOSED`.
