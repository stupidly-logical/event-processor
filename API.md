# API Contracts & Specifications

## Producer Service (Port 8080)

### Publish Single Event

**Endpoint:** `POST /api/v1/events/publish`

**Request:**
```json
{
  "type": "PushEvent" | "PullRequestEvent" | "IssuesEvent" | "WatchEvent" | "ForkEvent" | "CreateEvent",
  "actor": {
    "id": "number",
    "login": "string",
    "display_login": "string",
    "avatar_url": "string (URL)"
  },
  "repo": {
    "id": "number",
    "name": "string (owner/name)"
  },
  "payload": {
    // Event-specific (untyped JSON object)
    // Serialized to Avro in the SDK
  },
  "created_at": "string (ISO8601)"
}
```

**Response (202 Accepted):**
```json
{
  "event_id": "string (UUID)",
  "status": "ACCEPTED",
  "message": "Event queued for publishing"
}
```

**Error Responses:**

| Code | Reason |
|------|--------|
| 400 | Missing required fields (type, actor, repo, payload) |
| 422 | Invalid event type or actor.id/repo.id not numeric |
| 503 | Circuit breaker OPEN (Kafka unavailable) |
| 429 | Rate limit exceeded (future: sliding window ratelimit) |

**Metrics Recorded:**
- `ep.events.published` ++
- `ep.events.duplicate` ++ (if Redis hit)
- `ep.events.dlq_routed` ++ (if publish failed after retries)
- `ep.events.publish.duration` += latency

---

### Publish Batch

**Endpoint:** `POST /api/v1/events/batch-publish`

**Request:**
```json
{
  "events": [
    { /* same schema as single publish */ },
    { /* ... */ }
  ],
  "stop_on_first_error": false
}
```

**Response (202 Accepted):**
```json
{
  "total_events": 100,
  "accepted": 98,
  "rejected": 2,
  "errors": [
    {
      "index": 5,
      "reason": "Missing field: payload"
    },
    {
      "index": 42,
      "reason": "Invalid event type"
    }
  ]
}
```

---

### Generate & Publish Mock GH Archive Events

**Endpoint:** `POST /api/v1/events/generate`

**Request:**
```json
{
  "count": 1000,
  "event_types": [
    "PushEvent",
    "PullRequestEvent",
    "IssuesEvent",
    "WatchEvent"
  ],
  "repos": [
    "torvalds/linux",
    "rails/rails",
    "django/django"
  ]
}
```

**Response (202 Accepted):**
```json
{
  "generation_id": "string (UUID)",
  "status": "GENERATING",
  "total_to_generate": 1000,
  "scheduled_completion": "2024-01-01T12:10:30Z"
}
```

**Polling for completion:**
```bash
GET /api/v1/events/generate/{generation_id}/status

# Response
{
  "generation_id": "string",
  "status": "COMPLETED | IN_PROGRESS | FAILED",
  "generated_count": 950,
  "accepted_count": 945,
  "dlq_count": 5,
  "started_at": "2024-01-01T12:00:00Z",
  "completed_at": "2024-01-01T12:10:30Z",
  "metrics": {
    "success_rate": 0.994,
    "duplicate_rate": 0.001,
    "avg_publish_latency_ms": 45.2
  }
}
```

---

### Health & Readiness

**Endpoint:** `GET /actuator/health`

**Response (200 OK):**
```json
{
  "status": "UP",
  "components": {
    "kafka": {
      "status": "UP",
      "details": {
        "brokers": 3,
        "replication_factor": 3
      }
    },
    "redis": {
      "status": "UP"
    },
    "circuitBreaker": {
      "status": "UP",
      "details": {
        "state": "CLOSED",
        "failure_rate": 0.02,
        "window_size": 20
      }
    }
  }
}
```

---

### Metrics (Prometheus Text Format)

**Endpoint:** `GET /actuator/prometheus`

**Key Metrics:**

```
# Event Publishing Counters
ep_events_published_total{topic="events"} 1234567
ep_events_duplicate_total{topic="events"} 5234
ep_events_dlq_routed_total{reason="circuit_breaker_open"} 18
ep_events_dlq_routed_total{reason="max_retries_exceeded"} 142

# Latency Histograms (µs)
ep_events_publish_duration_seconds_bucket{le="0.01"} 1000
ep_events_publish_duration_seconds_bucket{le="0.05"} 45000
ep_events_publish_duration_seconds_bucket{le="0.1"} 98000

# Circuit Breaker
resilience4j_circuitbreaker_calls_total{name="kafka-publish",kind="successful"} 1234000
resilience4j_circuitbreaker_calls_total{name="kafka-publish",kind="failed"} 456
resilience4j_circuitbreaker_state{name="kafka-publish",state="CLOSED"} 1
resilience4j_circuitbreaker_failure_rate{name="kafka-publish"} 0.037

# Redis Dedup
redis_connected_clients 45
redis_used_memory_bytes 5242880
redis_evicted_keys_total 234
```

---

## Consumer Service (Port 8081)

### DLQ Status

**Endpoint:** `GET /api/v1/dlq/status`

**Response (200 OK):**
```json
{
  "depth": 34,
  "enqueued_total": 156,
  "replayed_total": 122,
  "permanently_failed": 0,
  "recent_entries": [
    {
      "event_id": "2489651624",
      "type": "PushEvent",
      "topic": "events",
      "reason": "circuit_breaker_open",
      "original_error": "Broker connection timeout",
      "enqueued_at": "2024-01-01T12:05:33Z",
      "retry_attempts": 3,
      "last_retry_at": "2024-01-01T12:06:00Z"
    }
  ],
  "metrics": {
    "avg_time_in_dlq_seconds": 45.2,
    "max_time_in_dlq_seconds": 234.5,
    "success_rate_on_replay": 0.97
  }
}
```

---

### Trigger DLQ Replay

**Endpoint:** `POST /api/v1/dlq/replay`

**Request:**
```json
{
  "max_entries": 100,
  "skip_failures": false
}
```

**Response (202 Accepted):**
```json
{
  "replay_id": "string (UUID)",
  "status": "REPLAY_STARTED",
  "entries_queued": 34,
  "scheduled_completion": "2024-01-01T12:10:30Z"
}
```

**Polling for completion:**
```bash
GET /api/v1/dlq/replay/{replay_id}/status

# Response
{
  "replay_id": "string",
  "status": "COMPLETED | IN_PROGRESS | FAILED",
  "total_entries": 34,
  "successful": 33,
  "failed": 1,
  "started_at": "2024-01-01T12:05:00Z",
  "completed_at": "2024-01-01T12:10:30Z"
}
```

---

### Aggregator Sink Lag

**Endpoint:** `GET /api/v1/aggregator/lag`

**Response:**
```json
{
  "lag_by_sink": {
    "billing": {
      "lag_records": 234,
      "lag_seconds": 45.2
    },
    "audit": {
      "lag_records": 156,
      "lag_seconds": 23.1
    },
    "analytics": {
      "lag_records": 890,
      "lag_seconds": 120.5
    }
  },
  "total_lag_records": 1280,
  "measured_at": "2024-01-01T12:10:00Z"
}
```

---

## Message Schema (Avro)

### GitHubEvent (Serialized)

```avsc
{
  "type": "record",
  "name": "GitHubEvent",
  "namespace": "com.eventprocessor.sdk.model",
  "fields": [
    {
      "name": "event_id",
      "type": "string",
      "doc": "Unique identifier (UUID)"
    },
    {
      "name": "event_type",
      "type": {
        "type": "enum",
        "name": "EventType",
        "symbols": ["PUSH", "PULL_REQUEST", "ISSUES", "WATCH", "FORK", "CREATE"]
      }
    },
    {
      "name": "actor_id",
      "type": "long"
    },
    {
      "name": "actor_login",
      "type": "string"
    },
    {
      "name": "repo_id",
      "type": "long"
    },
    {
      "name": "repo_name",
      "type": "string"
    },
    {
      "name": "payload",
      "type": "bytes",
      "doc": "JSON-serialized payload"
    },
    {
      "name": "created_at",
      "type": "long",
      "logicalType": "timestamp-millis"
    }
  ]
}
```

---

## Kafka Headers (Metadata)

Each event published to Kafka includes these headers:

| Header | Type | Example | Purpose |
|--------|------|---------|---------|
| `event-id` | String | `550e8400-e29b-41d4-a716-446655440000` | Idempotency key |
| `event-type` | String | `PUSH` | Event classifier |
| `traceparent` | String | `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01` | W3C distributed trace context |
| `correlation-id` | String | Same as event-id | Request correlation across services |

---

## Rate Limiting (Future)

Header-based rate limiting planned for future release:

```
X-RateLimit-Limit: 10000
X-RateLimit-Remaining: 9876
X-RateLimit-Reset: 1672531200
```

---

## Error Codes

| Code | Message | Action |
|------|---------|--------|
| 400 | `INVALID_REQUEST` | Check request schema, validate field types |
| 422 | `UNPROCESSABLE_ENTITY` | Semantic error (e.g., negative ID), fix and retry |
| 429 | `RATE_LIMIT_EXCEEDED` | Back off and retry with exponential jitter |
| 500 | `INTERNAL_ERROR` | Transient server error, retry |
| 503 | `SERVICE_UNAVAILABLE` | Circuit breaker OPEN, Kafka/Redis down. Retry after 30s |

---

## Examples

### cURL: Publish Event

```bash
curl -X POST http://localhost:8080/api/v1/events/publish \
  -H "Content-Type: application/json" \
  -d '{
    "type": "PushEvent",
    "actor": {
      "id": 1234,
      "login": "alice",
      "display_login": "alice",
      "avatar_url": "https://avatars.githubusercontent.com/u/1234"
    },
    "repo": {
      "id": 5678,
      "name": "alice/my-repo"
    },
    "payload": {
      "push_id": 123456789,
      "size": 3,
      "ref": "refs/heads/main",
      "head": "abc123def456...",
      "commits": [
        {
          "message": "Fix bug in parser"
        }
      ]
    },
    "created_at": "2024-01-01T12:00:00Z"
  }' -i
```

### Python: Batch Publish

```python
import requests
import json

events = [
    {
        "type": "PushEvent",
        "actor": {"id": i, "login": f"user{i}"},
        "repo": {"id": 100+i, "name": f"repo/project{i}"},
        "payload": {"push_id": 1000+i, "size": 1},
        "created_at": "2024-01-01T12:00:00Z"
    }
    for i in range(100)
]

response = requests.post(
    "http://localhost:8080/api/v1/events/batch-publish",
    json={"events": events}
)

print(response.json())
# Output:
# {
#   "total_events": 100,
#   "accepted": 100,
#   "rejected": 0,
#   "errors": []
# }
```

### JavaScript/Node.js: Publish with Retry

```javascript
const axios = require('axios');

async function publishWithRetry(event, maxRetries = 3) {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      const response = await axios.post(
        'http://localhost:8080/api/v1/events/publish',
        event
      );
      console.log(`Published: ${response.data.event_id}`);
      return response.data;
    } catch (error) {
      if (error.response?.status === 429 || error.response?.status === 503) {
        const delay = Math.pow(2, attempt) * 1000;
        console.log(`Retry attempt ${attempt + 1} after ${delay}ms`);
        await new Promise(r => setTimeout(r, delay));
      } else {
        throw error;
      }
    }
  }
}

// Usage
publishWithRetry({
  type: 'PushEvent',
  actor: { id: 123, login: 'alice' },
  repo: { id: 456, name: 'alice/repo' },
  payload: { push_id: 1, size: 1 },
  created_at: new Date().toISOString()
});
```

---

**Last Updated:** 2024-01-02
**Specification Version:** v1.0.0
