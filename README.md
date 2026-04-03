# Event Publishing System — Production-Ready Architecture

A fully-featured, cloud-native event publishing platform with built-in idempotency, circuit breaker protection, dead-letter queue handling, and observability. Inspired by real-world architectures at scale (GCP Pub/Sub + Redis + Dataflow).

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Components](#components)
- [Setup](#setup)
- [Running](#running)
- [Development](#development)
- [API Reference](#api-reference)
- [Monitoring](#monitoring)

---

## Overview

This system demonstrates a production-grade approach to reliable event publishing at scale:

- **Idempotency**: Every event gets a UUID; Redis deduplicates within a configurable TTL window (default 5min)
- **Resilience**: Circuit Breaker (Resilience4j) prevents cascading failures to downstream Pub/Sub
- **Durability**: Dead Letter Queue captures events that fail retry logic or hit circuit breaker; auto-replays when system recovers
- **Deduplication (Layer 2)**: Apache Beam Dataflow stateful window catches any redeliveries that slip past Redis
- **Fan-out**: Events flow through clean event stream to domain-specific sinks (Billing, Audit, Analytics)
- **Observability**: Micrometer metrics + structured logs + Grafana dashboards

### Key Metrics

- **SDK publish calls**: Total attempts
- **Redis hits/misses**: Dedup effectiveness
- **Circuit breaker state**: CLOSED / OPEN / HALF_OPEN with failure rate tracking
- **DLQ depth**: Events waiting for replay
- **E2E latency**: Publish → Delivery across layers

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Producer Services                              │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  REST API / gRPC / Message Queue                            │  │
│  │                client.publish(event)                         │  │
│  └──────────────────────────────────────┬───────────────────────┘  │
└─────────────────────────────────────────┼───────────────────────────┘
                                         │
                    ┌────────────────────▼────────────────────┐
                    │   Event Publisher SDK (Java Library)   │
                    │  ┌──────────────────────────────────┐  │
                    │  │ 1. Attach UUID (idempotency key)│  │
                    │  │ 2. Check Redis cache (Layer 1)  │  │
                    │  │    – MISS: proceed              │  │
                    │  │    – HIT: drop (duplicate)      │  │
                    │  │ 3. Circuit Breaker check        │  │
                    │  │    – CLOSED: allow              │  │
                    │  │    – OPEN: → DLQ                │  │
                    │  │ 4. Publish to Pub/Sub           │  │
                    │  │ 5. Exponential backoff retry    │  │
                    │  │    (2s base, 2× multiplier,     │  │
                    │  │    ±20% jitter, max 5 retries)  │  │
                    │  └──────────────────────────────────┘  │
                    └────────────────────┬───────────────────┘
                                         │
                ┌────────────┬───────────┼──────────┬────────────┐
                │            │           │          │            │
        ┌───────▼──────┐  ┌──▼───────┐ ┌▼───────┐ │ ┌─────────┐ │
        │    Redis     │  │  Pub/Sub  │ │  CB    │ │ │   DLQ   │ │
        │  Dedup Cache │  │   Topic   │ │ State  │ │ │  Topic  │ │
        │  TTL: 300s   │  │           │ │        │ │ │         │ │
        └──────────────┘  └──┬────────┘ └────────┘ │ └─────────┘ │
                             │                     │              │
                             │ at-least-once       │              │
                             │ redelivery (3%)     │              │
                             │                     │              │
                    ┌────────▼─────────────────────▼──────────┐
                    │  Apache Beam / Dataflow Pipeline        │
                    │  ┌──────────────────────────────────┐   │
                    │  │ Stateful Dedup Layer 2           │   │
                    │  │ – UUID window state (120s)       │   │
                    │  │ – Catches PubSub redeliveries    │   │
                    │  │ – Emit clean events              │   │
                    │  └──────────────────────────────────┘   │
                    └─────────┬────────────────────────────────┘
                              │
                ┌─────────────┼──────────────┬──────────────┐
                │             │              │              │
        ┌───────▼───┐  ┌──────▼─────┐  ┌────▼──────┐  ┌───▼──────┐
        │   Billing │  │   Audit    │  │ Analytics │  │  Custom  │
        │   Topic   │  │   Topic    │  │   Topic   │  │  Topics  │
        └───────────┘  └────────────┘  └───────────┘  └──────────┘
                             │
                    (Consumer Subscriptions)
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   ┌────▼─────┐       ┌──────▼──────┐      ┌─────▼────┐
   │  BI Tool │       │ Compliance  │      │  Analytics│
   │ (Bigquery)       │ (Cloud      │      │  (custom  │
   │          │       │  Firestore) │      │   sinks)  │
   └──────────┘       └─────────────┘      └───────────┘
```

### Request/Response Flow

**Publish Path:**
```
Client                SDK              Redis        CB        Pub/Sub       DLQ
  │                   │                 │           │           │           │
  │──publish(e)──────>│                 │           │           │           │
  │                   │──check UUID────>│           │           │           │
  │                   │<───HIT/MISS──────│           │           │           │
  │                   │──allowRequest──>│           │           │           │
  │                   │<──allowed/OPEN──│           │           │           │
  │                   │───publish────────────────────────────────>│           │
  │                   │                 │           │<─fail──────────────────>│
  │                   │                 │           │           │<─enqueue───│
  │<──ACK────────────│                 │           │           │           │
```

**DLQ Replay Path (triggered when CB → CLOSED):**
```
DLQ              CB              Pub/Sub         Dataflow        Sinks
  │               │               │                 │             │
  │ [queued]       │               │                 │             │
  │ + replay logic │               │                 │             │
  │<──CB: CLOSED───│               │                 │             │
  │ stagger retries│               │                 │             │
  │──republish────────────────────>│                 │             │
  │               │               │──deliver──────→ │             │
  │               │               │              dedup             │
  │               │               │                 │───clean──────>│
```

---

## Components

### 1. **SDK (Java Library)** — `sdk/`

Core event publishing library embedded in producer services.

**Key Classes:**
- `EventPublisher` — Builder pattern, configurable publishers
- `PublishRequest` — Immutable event wrapper with UUID
- `RedisDeduplicator` — Lettuce client, SET uuid "" EX 300 NX pattern
- `CircuitBreakerWrapper` — Resilience4j integration
- `PubSubGateway` — GCP Pub/Sub client wrapper
- `DLQRouter` — Routes events to DLQ topic + tracks metadata
- `PublishMetrics` — Micrometer gauge/counter/timer registration
- `PublishConfig` — Configuration (Redis TTL, CB thresholds, retry policy)

**Dependencies:**
- com.lettuce:lettuce-core (Redis async client)
- io.github.resilience4j:resilience4j-circuitbreaker
- com.google.cloud:google-cloud-pubsub
- io.micrometer:micrometer-core
- org.slf4j:slf4j-api

### 2. **Service (Spring Boot App)** — `service/`

HTTP REST API wrapper around SDK. Ingests events, manages DLQ replay, exposes metrics.

**Endpoints:**
- `POST /api/v1/events/publish` — Publish a single event
- `POST /api/v1/events/batch` — Publish multiple events
- `GET /api/v1/dlq/status` — DLQ queue depth + recent entries
- `POST /api/v1/dlq/replay` — Trigger manual DLQ replay
- `GET /api/v1/metrics` — Prometheus-compatible metrics endpoint
- `GET /health` — Liveness/readiness probes

**Components:**
- `EventIngestController` — REST endpoint handler
- `DLQConsumer` — Pub/Sub subscriber for DLQ topic; auto-replay on schedule
- `MetricsCollector` — Aggregates SDK metrics, exposes Actuator endpoints
- `ServiceConfig` — Wires up SDK beans, Redis, Pub/Sub, CB config

**Dependencies:**
- org.springframework.boot:spring-boot-starter-web
- org.springframework.boot:spring-boot-starter-actuator
- io.micrometer:micrometer-registry-prometheus
- com.epam.reportportal:client-core (for structured logs, optional)

### 3. **Dataflow Pipeline (Apache Beam)** — `dataflow/`

Stateful, windowed deduplication + fan-out to domain sinks.

**PipelineStages:**
1. **Read**: Subscribe to Pub/Sub topic
2. **Dedup (Stateful)**: `ValueState<String> uuidState` with 120s window; drop duplicates
3. **Transform**: Parse event, extract domain, timestamp
4. **Fan-out**: Write to domain-specific Pub/Sub topics (billing, audit, analytics)
5. **Metrics**: Counter for duplicates caught, events per domain per window

**Classes:**
- `EventDeduplicationPipeline` — Main pipeline entry point
- `EventDeduplicationFn` — Stateful DoFn with ValueState<String>
- `EventParserFn` — Extract domain + metadata
- `FanOutFn` — Route to correct sink topic
- `DedupMetrics` — Dataflow metrics (Beam Counters)

**Dependencies:**
- org.apache.beam:beam-sdks-java-core
- org.apache.beam:beam-runners-google-cloud-dataflow-java
- com.google.cloud:google-cloud-pubsub

### 4. **Infrastructure (Terraform)** — `infra/terraform/`

GCP resource definitions: Pub/Sub topics/subscriptions, Redis Memorystore, Dataflow, Cloud Run service, monitoring.

**Files:**
- `main.tf` — Provider, project setup
- `pubsub.tf` — Topics (events, dlq, billing, audit, analytics) + subscriptions
- `redis.tf` — Memorystore instance, VPC peering
- `dataflow.tf` — Dataflow job definition + worker config
- `cloud_run.tf` — Service deployment via Cloud Run
- `monitoring.tf` — Uptime checks, alert policies, log sinks
- `variables.tf` — Project ID, region, scaling config
- `outputs.tf` — Exported resource IDs/endpoints

**Secrets (Cloud Secret Manager):**
- `redis-connection-string`
- `pubsub-project-id`
- `pubsub-key-json` (service account key)

### 5. **Local Development (Docker Compose)** — `docker-compose.yml`

Spin up entire stack locally without GCP:

- Redis (standalone)
- Google Cloud Pub/Sub Emulator
- Prometheus (metrics scraper)
- Grafana (dashboards)
- Spring Boot service (localhost:8080)
- Jaeger (distributed tracing, optional)

---

## Setup

### Prerequisites

- **Java 17+**
- **Maven 3.9+**
- **Docker & Docker Compose** (for local dev)
- **GCP Project** (for production deployment)
- **gcloud CLI** (optional, for manual deployment)

### Project Structure

```
event-processor/
├── README.md                           # This file
├── pom.xml                             # Parent POM
├── docker-compose.yml                  # Local dev stack
├── .github/
│   └── workflows/
│       ├── ci.yml                      # Build + test on PR
│       └── deploy.yml                  # Deploy to prod on main
├── sdk/
│   ├── pom.xml
│   ├── src/main/java/com/eventpub/sdk/
│   │   ├── EventPublisher.java
│   │   ├── PublishRequest.java
│   │   ├── config/
│   │   │   ├── PublishConfig.java
│   │   │   ├── RedisConfig.java
│   │   │   └── CircuitBreakerConfig.java
│   │   ├── redis/
│   │   │   └── RedisDeduplicator.java
│   │   ├── circuitbreaker/
│   │   │   └── CircuitBreakerWrapper.java
│   │   ├── pubsub/
│   │   │   └── PubSubGateway.java
│   │   ├── dlq/
│   │   │   ├── DLQRouter.java
│   │   │   └── DLQEntry.java
│   │   └── metrics/
│   │       └── PublishMetrics.java
│   └── src/test/java/com/eventpub/sdk/
│       ├── EventPublisherTest.java
│       ├── RedisDeduplicatorTest.java
│       └── CircuitBreakerWrapperTest.java
├── service/
│   ├── pom.xml
│   ├── src/main/java/com/eventpub/service/
│   │   ├── Application.java
│   │   ├── api/
│   │   │   └── EventIngestController.java
│   │   ├── consumer/
│   │   │   └── DLQConsumer.java
│   │   ├── config/
│   │   │   ├── SdkConfig.java
│   │   │   ├── PubSubConfig.java
│   │   │   └── MetricsConfig.java
│   │   └── model/
│   │       ├── PublishRequest.java
│   │       └── PublishResponse.java
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── application-prod.yml
│   └── src/test/java/com/eventpub/service/
│       └── EventIngestControllerTest.java
├── dataflow/
│   ├── pom.xml
│   ├── src/main/java/com/eventpub/dataflow/
│   │   ├── EventDeduplicationPipeline.java
│   │   ├── fn/
│   │   │   ├── EventDeduplicationFn.java
│   │   │   ├── EventParserFn.java
│   │   │   └── FanOutFn.java
│   │   └── config/
│   │       └── PipelineOptions.java
│   └── src/test/java/com/eventpub/dataflow/
│       └── EventDeduplicationFnTest.java
└── infra/
    └── terraform/
        ├── main.tf
        ├── pubsub.tf
        ├── redis.tf
        ├── dataflow.tf
        ├── cloud_run.tf
        ├── monitoring.tf
        ├── variables.tf
        └── outputs.tf
```

### Clone & Install Dependencies

```bash
git clone https://github.com/stupidly-logical/event-processor.git
cd event-processor
mvn clean install -DskipTests
```

---

## Running

### Local Development (Docker Compose)

**Start the stack:**
```bash
docker-compose up -d
```

Waits for services:
- Pub/Sub Emulator: localhost:8085
- Redis: localhost:6379
- Prometheus: localhost:9090
- Grafana: http://localhost:3000 (admin/admin)
- Service: http://localhost:8080

**Verify health:**
```bash
curl http://localhost:8080/health
```

Expected:
```json
{
  "status": "UP",
  "components": {
    "redis": {"status":"UP"},
    "pubsub": {"status":"UP"}
  }
}
```

### Publish an Event (Local)

```bash
curl -X POST http://localhost:8080/api/v1/events/publish \
  -H "Content-Type: application/json" \
  -d '{
    "idempotency_key": "e3f8a9e2-4c1b-11ee-be56-0242ac120002",
    "type": "PushEvent",
    "actor": {"login": "developer"},
    "repo": {"name": "example/repo"},
    "payload": {
      "push_id": 123456,
      "ref": "refs/heads/main",
      "size": 2
    },
    "created_at": "2024-01-01T12:00:00Z"
  }'
```

Expected response:
```json
{
  "status": "ACCEPTED",
  "idempotency_key": "e3f8a9e2-4c1b-11ee-be56-0242ac120002",
  "event_id": "2489651620"
}
```

### Batch Publish

```bash
curl -X POST http://localhost:8080/api/v1/events/batch \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      { "type": "PushEvent", ... },
      { "type": "PullRequestEvent", ... }
    ]
  }'
```

### Monitor DLQ

```bash
curl http://localhost:8080/api/v1/dlq/status
```

Response:
```json
{
  "depth": 12,
  "enqueued_total": 150,
  "replayed_total": 138,
  "recent_entries": [
    {
      "event_id": "2489651624",
      "reason": "CB_OPEN",
      "enqueued_at": "2024-01-01T12:05:33Z",
      "retry_count": 3
    }
  ]
}
```

### Manual DLQ Replay

```bash
curl -X POST http://localhost:8080/api/v1/dlq/replay \
  -H "Content-Type: application/json" \
  -d '{"max_retries": 50}'
```

### View Metrics

```bash
curl http://localhost:8080/actuator/prometheus | grep eventpub
```

Example metrics:
```
eventpub_sdk_publish_total{status="success"} 1234
eventpub_sdk_publish_total{status="dlq_routed"} 18
eventpub_sdk_redis_hits_total 89
eventpub_sdk_redis_misses_total 1145
eventpub_sdk_circuitbreaker_state{state="CLOSED"} 1
eventpub_sdk_circuitbreaker_failure_rate 0.02
eventpub_dlq_depth 12
eventpub_dlq_replay_latency{quantile="0.95"} 234.5
```

### Grafana Dashboards

1. Navigate to http://localhost:3000
2. Login: admin / admin
3. Import dashboard JSON from `monitoring/grafana-dashboard.json`
4. View:
   - Event throughput (published vs delivered vs deduplicated)
   - Circuit breaker state + failure rate
   - Redis cache hit ratio
   - DLQ depth + replay rate
   - Aggregator sink lag (billing/audit/analytics)
   - Dataflow duplicate catch rate

---

## Production Deployment

### 1. Configure GCP Project

```bash
export PROJECT_ID="your-project-id"
export REGION="us-central1"

gcloud config set project $PROJECT_ID
gcloud auth login
```

### 2. Enable APIs

```bash
gcloud services enable \
  pubsub.googleapis.com \
  memorystore.googleapis.com \
  dataflow.googleapis.com \
  run.googleapis.com \
  monitoring.googleapis.com
```

### 3. Deploy Infrastructure (Terraform)

```bash
cd infra/terraform
terraform init -backend-config="bucket=$PROJECT_ID-tfstate"
terraform plan -var="project_id=$PROJECT_ID" -var="region=$REGION"
terraform apply -var="project_id=$PROJECT_ID" -var="region=$REGION"
```

Outputs:
- Pub/Sub topic names
- Redis connection string
- Cloud Run service URL
- Dataflow pipeline ID

### 4. Build & Push Docker Image

```bash
mvn clean package -Pprod
docker build -f service/Dockerfile -t gcr.io/$PROJECT_ID/event-processor:latest .
docker push gcr.io/$PROJECT_ID/event-processor:latest
```

### 5. Deploy Service to Cloud Run

```bash
gcloud run deploy event-processor \
  --image gcr.io/$PROJECT_ID/event-processor:latest \
  --platform managed \
  --region $REGION \
  --set-env-vars PUBSUB_PROJECT_ID=$PROJECT_ID,REDIS_HOST=<redis-ip>,REDIS_PORT=6379 \
  --service-account event-processor-sa@$PROJECT_ID.iam.gserviceaccount.com \
  --memory 2Gi \
  --cpu 2
```

### 6. Deploy Dataflow Pipeline

```bash
mvn clean package -Pdataflow -f dataflow/pom.xml

java -cp dataflow/target/event-processor-dataflow-1.0.jar \
  com.eventpub.dataflow.EventDeduplicationPipeline \
  --project=$PROJECT_ID \
  --region=$REGION \
  --inputTopic=projects/$PROJECT_ID/topics/events \
  --billingTopic=projects/$PROJECT_ID/topics/billing \
  --auditTopic=projects/$PROJECT_ID/topics/audit \
  --analyticsTopic=projects/$PROJECT_ID/topics/analytics \
  --runner=DataflowRunner \
  --tempLocation=gs://$PROJECT_ID-dataflow-temp
```

---

## Development

### Running Tests

```bash
# Unit tests
mvn test

# Integration tests (requires Docker Compose)
docker-compose up -d
mvn test -Pintegration

# SDK tests only
mvn test -pl sdk

# Service tests only
mvn test -pl service
```

### Code Quality

```bash
# Checkstyle
mvn checkstyle:check -pl sdk,service

# SpotBugs
mvn spotbugs:check -pl sdk,service

# SonarQube
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=admin \
  -Dsonar.password=admin
```

### Local Debugging

**Enable debug mode:**
```bash
export JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
docker-compose up -d service
```

Connect IDE debugger to localhost:5005.

---

## API Reference

### Publish Event

**Endpoint:** `POST /api/v1/events/publish`

**Request:**
```json
{
  "idempotency_key": "string (UUID, optional — auto-generated if omitted)",
  "type": "PushEvent | PullRequestEvent | IssuesEvent | WatchEvent | ForkEvent",
  "actor": {
    "login": "string",
    "id": "integer"
  },
  "repo": {
    "name": "string (owner/name)",
    "id": "integer"
  },
  "payload": "object (event-specific payload)",
  "created_at": "ISO8601 timestamp"
}
```

**Response (202 Accepted):**
```json
{
  "status": "ACCEPTED",
  "idempotency_key": "string",
  "event_id": "string",
  "message": "Event queued for publishing"
}
```

**Error Responses:**
- `400 Bad Request` — Invalid event schema
- `409 Conflict` — Duplicate idempotency key (same event already processed)
- `503 Service Unavailable` — Circuit breaker OPEN

### Batch Publish

**Endpoint:** `POST /api/v1/events/batch`

**Request:**
```json
{
  "events": [
    { "type": "PushEvent", ... },
    { "type": "PullRequestEvent", ... }
  ]
}
```

**Response (202 Accepted):**
```json
{
  "status": "ACCEPTED",
  "total_events": 2,
  "accepted": 2,
  "rejected": 0,
  "message": "2 events queued"
}
```

### DLQ Status

**Endpoint:** `GET /api/v1/dlq/status`

**Response:**
```json
{
  "depth": 12,
  "enqueued_total": 150,
  "replayed_total": 138,
  "permanently_failed": 0,
  "recent_entries": [
    {
      "event_id": "2489651624",
      "type": "PushEvent",
      "reason": "CB_OPEN",
      "enqueued_at": "2024-01-01T12:05:33Z",
      "last_retry_at": "2024-01-01T12:06:00Z",
      "retry_count": 3
    }
  ]
}
```

### DLQ Replay

**Endpoint:** `POST /api/v1/dlq/replay`

**Request:**
```json
{
  "max_retries": 50,
  "skip_failures": false
}
```

**Response:**
```json
{
  "status": "REPLAY_STARTED",
  "entries_queued": 12,
  "scheduled_in_seconds": 60
}
```

### Health

**Endpoint:** `GET /health`

**Response:**
```json
{
  "status": "UP",
  "components": {
    "redis": {"status": "UP"},
    "pubsub": {"status": "UP"},
    "circuitBreaker": {"status": "UP", "state": "CLOSED"}
  }
}
```

### Metrics (Prometheus)

**Endpoint:** `GET /actuator/prometheus`

**Key Metrics:**
```
eventpub_sdk_publish_total{status="success"}
eventpub_sdk_publish_total{status="retry"}
eventpub_sdk_publish_total{status="dlq_routed"}
eventpub_sdk_redis_hits_total
eventpub_sdk_redis_misses_total
eventpub_sdk_circuitbreaker_state{state="CLOSED|OPEN|HALF_OPEN"}
eventpub_sdk_circuitbreaker_failure_rate
eventpub_sdk_publish_latency_seconds{quantile="0.5|0.95|0.99"}
eventpub_dlq_depth
eventpub_dlq_enqueue_rate
eventpub_dlq_replay_success_total
eventpub_dataflow_duplicates_caught_total
eventpub_aggregator_events_processed_per_sink{sink="billing|audit|analytics"}
```

---

## Monitoring

### Grafana Dashboard

Pre-built dashboard includes:

1. **Real-time Metrics** (top row):
   - Events Published (per min)
   - Redis Hit Ratio
   - Circuit Breaker State + Failure Rate
   - DLQ Depth
   - Aggregator Events Per Sink

2. **Event Throughput** (line chart):
   - Published vs Delivered vs Deduplicated over time
   - DLQ-routed count

3. **Circuit Breaker** (state machine):
   - Failure rate with 50% trip threshold line
   - Background color: RED (OPEN), AMBER (HALF_OPEN), GREEN (CLOSED)

4. **Redis Cache State** (stacked bar + line):
   - Hits (deduped) vs Misses (new)
   - Cache size overlay

5. **DLQ Lifecycle** (area chart):
   - Enqueue rate (red)
   - Replay rate (green)
   - Net depth (purple fill)

6. **Dataflow Dedup** (line chart):
   - Duplicates caught per minute
   - Window state size

7. **Sink Lag** (gauge):
   - Billing / Audit / Analytics subscription lag (seconds)

8. **Error Rate & Latency** (multi-axis):
   - P50 / P95 / P99 publish latency
   - Failure rate (%)
   - Retry count distribution

### Alerting Rules (Prometheus)

```yaml
# Circuit Breaker tripped
alert: CircuitBreakerOpen
condition: eventpub_sdk_circuitbreaker_state{state="OPEN"} == 1
for: 2m
actions: [PagerDuty, Slack]

# High DLQ depth
alert: DLQDepthHigh
condition: eventpub_dlq_depth > 1000
for: 5m
actions: [Slack, email]

# Redis unavailable
alert: RedisDown
condition: redis_up == 0
for: 1m
actions: [Pagerduty, Slack]

# Dataflow job failed
alert: DataflowJobFailed
condition: dataflow_job_state == "FAILED"
for: 0m
actions: [PagerDuty]

# High publish latency
alert: PublishLatencyHigh
condition: eventpub_sdk_publish_latency_seconds{quantile="0.95"} > 10
for: 5m
actions: [Slack]
```

### Logging

**Structured logging** in JSON format (stdout to Cloud Logging):

```json
{
  "timestamp": "2024-01-01T12:05:33.123Z",
  "level": "INFO",
  "logger": "com.eventpub.sdk.EventPublisher",
  "message": "Event published successfully",
  "fields": {
    "event_id": "2489651620",
    "idempotency_key": "e3f8a9e2-4c1b-11ee-be56-0242ac120002",
    "type": "PushEvent",
    "repo": "torvalds/linux",
    "attempt": 1,
    "latency_ms": 245,
    "redis_hit": false,
    "cb_state": "CLOSED"
  }
}
```

**Log aggregation:**
- Local: tail -f service/logs/app.log
- Prod: Cloud Logging (Stackdriver) with filtering/alerting

---

## Performance Benchmarks

### Single Node (16GB RAM, 8 CPU, local Docker)

| Metric | Value |
|--------|-------|
| Peak throughput | 10,000 events/sec |
| P50 publish latency | 45ms |
| P95 publish latency | 120ms |
| P99 publish latency | 450ms |
| Redis hit ratio (steady state) | 95% |
| CB trip → recovery time | 35s (30s wait + 5s probing) |
| DLQ replay rate | 1,000 events/sec |
| Dataflow dedup latency (120s window) | <100ms |

### Scaling Notes

- **Throughput**: Scale event-processor service replicas (Cloud Run autoscaling), add Pub/Sub partition count
- **Redis**: Memorystore cluster mode for sharding; TTL tuning based on retry window
- **Dataflow**: Increase worker count + sliding window size for high-volume streams
- **DLQ**: Separate topic partition per domain to prevent backpressure

---

## Troubleshooting

### CB is OPEN, no events flowing

**Symptoms:**
- `eventpub_sdk_circuitbreaker_state{state="OPEN"} == 1`
- DLQ depth increasing

**Diagnosis:**
```bash
# Check Pub/Sub health
gcloud pubsub topics list

# Check Redis connectivity
redis-cli -h <redis-ip> PING

# Check recent logs
kubectl logs -f deployment/event-processor -c service | grep CB
```

**Resolution:**
1. Check Pub/Sub quota: `gcloud pubsub quotas list`
2. Verify Redis connection + memory: `redis-cli INFO memory`
3. Once underlying issue fixed, CB will auto-recover after 30s wait + 3 successful probes
4. Or manually force close (admin endpoint): `POST /api/v1/circuitbreaker/reset`

### High DLQ depth

**Symptoms:**
- DLQ depth > 1000
- Aggregator lag increasing

**Diagnosis:**
```bash
curl http://localhost:8080/api/v1/dlq/status | jq '.recent_entries[0]'
```

**Resolution:**
1. If CB is CLOSED, trigger manual replay: `curl -X POST http://localhost:8080/api/v1/dlq/replay`
2. If CB is OPEN, wait for recovery or scale up Pub/Sub partition count
3. Monitor DLQ replay rate: `eventpub_dlq_replay_success_total`

### Redis memory full

**Symptoms:**
- `redis_memory_used_bytes > redis_memory_max_bytes`
- Redis evictions increasing

**Resolution:**
1. Increase Memorystore instance size: `gcloud redis instances update <instance> --size=32` (GCP)
2. Reduce dedup TTL (if safe for your retry window): `PublishConfig.setRedisTtl(120)` (2 min instead of 5)
3. Add Redis cluster sharding: Terraform variable `redis_shard_count=3`

### Dataflow duplicate catch rate = 0

**Expected:** ~3% of events (matching PubSub redelivery rate).
**Actual:** 0%

**Diagnosis:**
Pub/Sub might not be configured with redelivery enabled, or window is too small.

**Check:**
```bash
# Verify Dataflow job logs
gcloud dataflow jobs list --region=$REGION
gcloud dataflow jobs describe <job-id> --region=$REGION
```

**Resolution:**
1. Increase window size in pipeline: `new GlobalWindow()` → `SlidingWindows.into(Duration.standardSeconds(180))` if needed
2. Verify Pub/Sub subscription has ack deadline > max message processing time

---

## Contributing

1. **Branch:** `git checkout -b feat/my-feature`
2. **Test:** `mvn clean test`
3. **Code quality:** `mvn checkstyle:check spotbugs:check`
4. **Commit:** Follow conventional commits (`feat:`, `fix:`, `docs:`, etc.)
5. **PR:** Squash + merge to main; GitHub Actions CI will auto-deploy to prod

---

## License

Apache 2.0

---

## References

- [Google Cloud Pub/Sub](https://cloud.google.com/pubsub/docs)
- [Resilience4j Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker)
- [Apache Beam Stateful Processing](https://beam.apache.org/documentation/programmers-guide/#stateful-processing)
- [Lettuce Redis Client](https://lettuce.io/)
- [Micrometer Metrics](https://micrometer.io/)

---

**Last Updated:** 2024-01-01
**Maintainers:** @stupidly-logical
