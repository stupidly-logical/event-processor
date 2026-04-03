# Development & Deployment Guide

## Quick Start (Local Development)

### Prerequisites

```bash
# Check Java version
java -version  # Should be 21+

# Check Maven version
mvn -version   # Should be 3.9+

# Docker & Docker Compose
docker --version
docker-compose --version
```

### 1. Clone & Install

```bash
git clone https://github.com/stupidly-logical/event-processor.git
cd event-processor
mvn clean install -DskipTests
```

### 2. Start Local Stack

```bash
# From project root
docker-compose -f observability/docker-compose.yml up -d

# Verify services are ready
curl http://localhost:9092/health          # Kafka (Redpanda)
curl http://localhost:6379                # Redis
curl http://localhost:9090/api/v1/health  # Prometheus

# Wait 30s for Kafka broker to stabilize
sleep 30
```

### 3. Start Applications

**Terminal 1: Producer Service (Port 8080)**
```bash
cd producer-service
mvn spring-boot:run
# Wait for "Started ProducerApplication"
```

**Terminal 2: Consumer Service (Port 8081)**
```bash
cd consumer-service
mvn spring-boot:run
# Wait for "Started ConsumerApplication"
```

### 4. Verify Everything

```bash
# Health checks
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health

# Metrics endpoints
curl http://localhost:8080/actuator/prometheus | grep ep_events
curl http://localhost:8081/actuator/prometheus | grep ep_events
```

### 5. Ingest Events

**Single Event:**
```bash
curl -X POST http://localhost:8080/api/v1/events/publish \
  -H "Content-Type: application/json" \
  -d '{
    "type": "PushEvent",
    "actor": {"login": "torvalds"},
    "repo": {"name": "linux/linux"},
    "payload": {
      "push_id": 123456,
      "ref": "refs/heads/main",
      "size": 5
    }
  }'
```

**Batch (100 events):**
```bash
curl -X POST http://localhost:8080/api/v1/events/batch-publish \
  -H "Content-Type: application/json" \
  -d '{
    "count": 100
  }'
```

### 6. Monitor

**Metrics Dashboard (Prometheus)**
- Open http://localhost:9090
- Query: `ep_events_published` + `ep_events_duplicate` + `ep_events_dlq_routed`

**Logs Viewer (Redpanda Console)**
- Open http://localhost:8080 (Redpanda Console mapped to 8080 in docker-compose)
- View topics: `events`, `dlq`, `billing`, `audit`, `analytics`

### 7. Shutdown

```bash
docker-compose -f observability/docker-compose.yml down -v
# Kill background processes in terminals
```

---

## Project Structure Explanation

```
event-processor/
├── sdk/                          # Core library (published to Maven Central in prod)
│   ├── src/main/java/
│   │   └── com/eventprocessor/sdk/
│   │       ├── publisher/        # EventPublisher entry point
│   │       ├── dedup/            # RedisDeduplicator (Layer 1)
│   │       ├── cb/               # CircuitBreakerConfiguration
│   │       ├── dlq/              # DLQ routing logic
│   │       ├── model/            # DlqEvent DTO
│   │       └── SdkAutoConfiguration.java  # Spring Boot autoconfigure
│   └── src/test/java/            # Unit tests
│
├── producer-service/             # REST API + Event Ingestion
│   ├── src/main/java/
│   │   └── com/eventprocessor/producer/
│   │       ├── api/              # REST endpoints
│   │       ├── config/           # Kafka producer config
│   │       ├── generator/        # GH Archive data generator
│   │       └── ProducerApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml       # Local config
│   │   └── application-prod.yml  # Prod overrides
│   └── Dockerfile                # Container image
│
├── consumer-service/             # Event Consumption + DLQ Replay
│   ├── src/main/java/
│   │   └── com/eventprocessor/consumer/
│   │       ├── listener/         # Kafka consumer listeners
│   │       ├── dlq/              # DLQ replay scheduler
│   │       ├── config/           # Spring Cloud Stream config
│   │       └── ConsumerApplication.java
│   └── src/main/resources/
│       └── application.yml
│
├── observability/                # Docker Compose + Monitoring
│   ├── docker-compose.yml        # Kafka, Redis, Prometheus, console
│   ├── prometheus.yml            # Prometheus scrape config
│   ├── otel-collector-config.yaml # OpenTelemetry collector
│   └── grafana/                  # Dashboard JSON definitions
│       └── dashboards/
│           ├── event-throughput.json
│           ├── circuit-breaker.json
│           ├── redis-cache-state.json
│           └── dlq-lifecycle.json
│
├── infra/                        # Terraform (GCP production)
│   └── terraform/
│       ├── main.tf
│       ├── kafka.tf              # Cloud Dataproc config
│       ├── redis.tf              # Memorystore
│       ├── cloud_run.tf          # Cloud Run services
│       ├── monitoring.tf         # Alerting policies
│       ├── variables.tf
│       └── outputs.tf
│
├── .github/workflows/
│   ├── ci.yml                    # Build + test + SonarQube
│   └── deploy.yml                # Deploy to prod
│
└── pom.xml                       # Parent Maven POM
```

---

## Understanding the Architecture

### 1. **Event Ingestion (Producer)**

```
Client Request
    ↓
POST /api/v1/events/publish
    ↓
EventPublisher SDK
    ├─→ Redis dedup check (Layer 1)
    │   ├─ HIT: drop (duplicate)
    │   └─ MISS: proceed
    ├─→ Circuit breaker check
    │   ├─ CLOSED: allow
    │   ├─ OPEN: route to DLQ
    │   └─ HALF_OPEN: probe
    ├─→ Serialize to Avro
    ├─→ Publish to Kafka topic
    ├─→ Exponential backoff retry on failure
    │   (base 2s, multiplier 2×, ±20% jitter, max 5 retries)
    └─→ On final failure: route to DLQ
```

### 2. **Event Processing (Consumer)**

```
Kafka Topic (events)
    ↓
Consumer (Spring Cloud Stream)
    ├─→ Dedup check (Layer 2)
    │   ├─ Stateful window (120s)
    │   ├─ Catches Kafka redeliveries
    │   └─ Drop if duplicate
    ├─→ Domain routing (extract repo → billing/audit/analytics)
    ├─→ Fan-out to sink topics
    │   ├─ billing-events
    │   ├─ audit-events
    │   └─ analytics-events
    └─→ Emit metrics + traces
```

### 3. **DLQ Replay (Background)**

```
DLQ Topic (dlq)
    ↓
DLQ Consumer Listener
    ├─→ When CB → CLOSED: auto-replay
    ├─→ Stagger retries (exponential backoff)
    ├─→ Max retries per entry: 5
    └─→ Move to permanent failure bucket if exhausted
```

---

## Running Tests

### Unit Tests (Fast, no containers)

```bash
mvn test
```

### Integration Tests (Requires Docker Compose)

```bash
# Start stack
docker-compose -f observability/docker-compose.yml up -d

# Run integration tests
mvn test -Pintegration

# Stop stack
docker-compose -f observability/docker-compose.yml down -v
```

### Test Coverage

```bash
mvn clean test jacoco:report
open sdk/target/site/jacoco/index.html
open producer-service/target/site/jacoco/index.html
open consumer-service/target/site/jacoco/index.html
```

### Performance Testing

```bash
# Generate 10,000 events and measure latency distribution
curl -X POST http://localhost:8080/api/v1/events/batch-publish \
  -H "Content-Type: application/json" \
  -d '{"count": 10000}'

# Monitor metrics during run
watch -n1 'curl -s http://localhost:9090/api/v1/query?query=ep_events_published | jq .'
```

---

## Code Quality

### Static Analysis

```bash
# Checkstyle
mvn checkstyle:check

# SpotBugs
mvn spotbugs:check

# Both
mvn clean verify
```

### Format Code

```bash
# Check formatting
mvn spotless:check

# Auto-fix formatting
mvn spotless:apply
```

### SonarQube (Optional)

```bash
# Start SonarQube container
docker run -d \
  -p 9000:9000 \
  --name sonarqube \
  sonarqube:lts

# Wait for startup
sleep 30

# Analyze
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=admin \
  -Dsonar.password=admin

# Open http://localhost:9000
```

---

## Production Deployment

### Prerequisites

```bash
export PROJECT_ID=your-gcp-project
export REGION=us-central1

gcloud auth login
gcloud config set project $PROJECT_ID
```

### 1. Enable APIs

```bash
gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  cloudrun.googleapis.com \
  memcache.googleapis.com \
  dataflow.googleapis.com \
  monitoring.googleapis.com
```

### 2. Build & Push Images

```bash
# Create Artifact Registry repo
gcloud artifacts repositories create event-processor \
  --repository-format docker \
  --location $REGION

# Build images
mvn clean package -Pprod

# Tag and push
docker build -t $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/producer:latest \
  -f producer-service/Dockerfile producer-service/

docker build -t $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/consumer:latest \
  -f consumer-service/Dockerfile consumer-service/

docker push $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/producer:latest
docker push $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/consumer:latest
```

### 3. Deploy Infrastructure (Terraform)

```bash
cd infra/terraform

terraform init

terraform plan \
  -var="project_id=$PROJECT_ID" \
  -var="region=$REGION"

terraform apply \
  -var="project_id=$PROJECT_ID" \
  -var="region=$REGION"

# Capture outputs
KAFKA_BROKERS=$(terraform output -raw kafka_brokers)
REDIS_HOST=$(terraform output -raw redis_host)
```

### 4. Deploy Services to Cloud Run

```bash
# Producer Service
gcloud run deploy event-processor-producer \
  --image $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/producer:latest \
  --platform managed \
  --region $REGION \
  --memory 2Gi \
  --cpu 2 \
  --concurrency 100 \
  --set-env-vars KAFKA_BROKERS=$KAFKA_BROKERS,REDIS_HOST=$REDIS_HOST \
  --service-account event-processor-sa@$PROJECT_ID.iam.gserviceaccount.com

# Consumer Service
gcloud run deploy event-processor-consumer \
  --image $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/consumer:latest \
  --platform managed \
  --region $REGION \
  --memory 2Gi \
  --cpu 2 \
  --concurrency 50 \
  --set-env-vars KAFKA_BROKERS=$KAFKA_BROKERS \
  --service-account event-processor-sa@$PROJECT_ID.iam.gserviceaccount.com
```

### 5. Verify Production

```bash
# Get Cloud Run URLs
PRODUCER_URL=$(gcloud run services describe event-processor-producer \
  --region $REGION --format='value(status.url)')

# Test API
curl -X POST $PRODUCER_URL/api/v1/events/publish \
  -H "Content-Type: application/json" \
  -d '{"type": "PushEvent", "repo": {"name": "test/repo"}}'

# View metrics in Cloud Monitoring
# https://console.cloud.google.com/monitoring
```

---

## Debugging

### Enable Debug Logging

Set in `application.yml`:
```yaml
logging:
  level:
    root: WARN
    com.eventprocessor: DEBUG
    org.apache.kafka: INFO
    io.github.resilience4j: DEBUG
```

### Connect IDE Debugger

```bash
export JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"

mvn spring-boot:run

# In IDE: Run → Debug Configurations → Remote JVM Debug
# Host: localhost, Port: 5005
```

### View Live Kafka Topics

```bash
# List topics
docker exec -it redpanda /opt/redpanda/bin/rpk topic list

# Consume events topic
docker exec -it redpanda /opt/redpanda/bin/rpk topic consume events

# Consume DLQ topic
docker exec -it redpanda /opt/redpanda/bin/rpk topic consume dlq
```

### Check Redis Dedup Cache

```bash
redis-cli -h localhost -p 6379

# List all dedup keys
KEYS dedup:*

# Check TTL on a key
TTL dedup:events:abc123

# Monitor cache hits/misses
INFO stats
```

---

## Troubleshooting

### "Circuit Breaker is OPEN"

**Symptom:** Events being routed to DLQ immediately

**Check:**
```bash
curl http://localhost:8080/actuator/prometheus | grep circuitbreaker

# Should show: resilience4j_circuitbreaker_state{state="CLOSED"} 1
```

**Fix:**
1. Check Kafka broker health: `docker logs redpanda`
2. Check Redis health: `redis-cli PING`
3. CB will auto-recover after 30s + 3 successful probes
4. Or manually reset: `curl -X POST http://localhost:8080/api/admin/circuitbreaker/reset`

### "DLQ is growing"

**Check depth:**
```bash
curl http://localhost:8081/api/v1/dlq/status

# If depth > 1000: manual replay
curl -X POST http://localhost:8081/api/v1/dlq/replay \
  -H "Content-Type: application/json" \
  -d '{"max_retries": 100}'
```

### "High Memory Usage"

**Check:**
```bash
redis-cli INFO memory

# If used > 80% of maxmemory:
# 1. Reduce dedup TTL: redisConfig.setTimeToLive(Duration.ofSeconds(120))
# 2. Scale up Redis instance
# 3. Add Redis cluster sharding
```

### "Duplicate Events Not Caught"

**Expected:** ~2-3% duplicates (from Kafka redelivery)
**Actual:** 0%

**Diagnosis:**
```bash
# Check Layer 2 dedup metrics
curl http://localhost:8081/actuator/prometheus | grep dedup

# Expected: ep_dedup_layer2_duplicates_caught_total > 0
```

**Fix:**
- Increase consumer lag tolerance
- Scale up consumer replicas
- Check Kafka redelivery configuration

---

## Contributing

1. **Create a feature branch:**
   ```bash
   git checkout -b feat/my-feature
   ```

2. **Develop locally:**
   ```bash
   docker-compose -f observability/docker-compose.yml up -d
   # Make changes
   mvn clean test
   ```

3. **Code quality:**
   ```bash
   mvn spotless:apply checkstyle:check spotbugs:check
   ```

4. **Commit with conventional message:**
   ```bash
   git add .
   git commit -m "feat: add new feature" # or fix:, docs:, test:, refactor:
   ```

5. **Push and create PR:**
   ```bash
   git push origin feat/my-feature
   # Create PR on GitHub
   ```

6. **CI will run automatically:**
   - Build + test
   - SonarQube analysis
   - Code coverage report
   - Container image build

---

## Release Process

1. **Tag release:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

2. **GitHub Actions automatically:**
   - Runs full test suite
   - Publishes SDK to Maven Central
   - Builds and pushes container images
   - Deploys to production

3. **Verify release:**
   ```bash
   # Check Maven Central
   https://repo1.maven.org/maven2/com/eventprocessor/event-processor-parent/1.0.0/

   # Check container registry
   docker pull gcr.io/$PROJECT_ID/event-processor:v1.0.0
   ```

---

## Performance Tuning

### Producer Throughput

**Bottleneck:** Circuit breaker / Kafka ACK time

```yaml
# In application.yml
spring.kafka.producer:
  acks: all                    # Durability vs latency tradeoff
  batch-size: 32768           # Increase for throughput
  linger-ms: 10               # Batch timeout
  buffer-memory: 67108864     # Increase for high throughput
```

### Consumer Lag

```yaml
spring.cloud.stream:
  kafka.bindings.input:
    consumer.fetch-min-bytes: 1024
    consumer.fetch-max-wait-ms: 500
    consumer.max-poll-records: 500  # Increase for throughput
```

### Redis Dedup

```yaml
redis:
  ttl-seconds: 300          # Reduce for lower memory
  max-connections: 100      # Increase for high concurrency
  connection-pool-size: 50
```

---

**Last Updated:** 2024-01-02
