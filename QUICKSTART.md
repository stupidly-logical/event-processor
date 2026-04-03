# QUICK START

Get the entire event publishing system running in **3 commands, 5 minutes**.

## 1. Start Local Stack

```bash
git clone https://github.com/stupidly-logical/event-processor.git
cd event-processor
docker-compose -f observability/docker-compose.yml up -d
```

Wait ~30 seconds for Kafka to stabilize.

## 2. Start Services

**Terminal 1:**
```bash
cd producer-service && mvn spring-boot:run
# Waits for "Started ProducerApplication"
```

**Terminal 2:**
```bash
cd consumer-service && mvn spring-boot:run
# Waits for "Started ConsumerApplication"
```

## 3. Test It

```bash
# Publish a single event
curl -X POST http://localhost:8080/api/v1/events/publish \
  -H "Content-Type: application/json" \
  -d '{
    "type": "PushEvent",
    "actor": {"id": 123, "login": "alice"},
    "repo": {"id": 456, "name": "alice/repo"},
    "payload": {"push_id": 1, "size": 1, "ref": "refs/heads/main"},
    "created_at": "2024-01-01T00:00:00Z"
  }'

# Check health
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health

# View metrics
curl http://localhost:8080/actuator/prometheus | grep ep_events

# Check DLQ status
curl http://localhost:8081/api/v1/dlq/status
```

## 4. Monitor (Optional)

Open http://localhost:3000 (Grafana)
- Login: admin / admin
- View pre-built dashboards

## 5. Publish More Events

```bash
# Batch of 100 realistic events
curl -X POST http://localhost:8080/api/v1/events/batch-publish \
  -H "Content-Type: application/json" \
  -d '{"count": 100}'

# Watch metrics live
watch -n1 'curl -s http://localhost:9090/api/v1/query?query=ep_events_published'
```

## 6. Simulate Failures

```bash
# Set PubSub to 80% failure (triggers circuit breaker)
curl -X POST http://localhost:8080/api/admin/faults/pubsub-failure \
  -H "Content-Type: application/json" \
  -d '{"failure_rate": 0.80}'

# Watch DLQ fill up, then recover
curl http://localhost:8081/api/v1/dlq/status

# Reset
curl -X POST http://localhost:8080/api/admin/circuitbreaker/reset
```

## Next Steps

- **Learn the architecture**: Read `README.md`
- **Explore the code**: Start with `sdk/src/main/java/com/eventprocessor/sdk/publisher/EventPublisher.java`
- **Deploy to GCP**: See `DEVELOPMENT.md` "Production Deployment" section
- **Contribute**: See `CONTRIBUTING.md`

## Cleanup

```bash
docker-compose -f observability/docker-compose.yml down -v
# Kill background processes in terminals
```

---

**That's it!** You now have a fully operational event publishing system with:
- ✅ Idempotent publishing (Redis dedup)
- ✅ Circuit breaker protection
- ✅ Dead Letter Queue replay
- ✅ Metrics & dashboards
- ✅ Multi-sink fan-out
- ✅ Distributed tracing

Explore the code, modify, test locally, and deploy to production.
