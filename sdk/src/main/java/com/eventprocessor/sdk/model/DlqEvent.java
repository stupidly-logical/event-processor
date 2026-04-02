package com.eventprocessor.sdk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * JPA entity persisted to dlq_events when a Kafka publish fails all retries.
 */
@Entity
@Table(name = "dlq_events")
public class DlqEvent {

    public enum Status { PENDING, REPLAYING, REPLAYED, DEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "payload", nullable = false, columnDefinition = "bytea")
    private byte[] payload;

    @Column(name = "trace_context", length = 512)
    private String traceContext;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "original_occurred_at", nullable = false)
    private Instant originalOccurredAt;

    protected DlqEvent() { }

    public DlqEvent(String eventId, String eventType, String topic, byte[] payload,
                    String traceContext, String failureReason, Instant originalOccurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.traceContext = traceContext;
        this.failureReason = failureReason;
        this.originalOccurredAt = originalOccurredAt;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public byte[] getPayload() { return payload; }
    public String getTraceContext() { return traceContext; }
    public String getFailureReason() { return failureReason; }
    public int getRetryCount() { return retryCount; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getOriginalOccurredAt() { return originalOccurredAt; }

    // ── Mutators used by the replayer ────────────────────────────────────────

    public void markReplaying() {
        this.status = Status.REPLAYING;
        this.updatedAt = Instant.now();
    }

    public void markReplayed() {
        this.status = Status.REPLAYED;
        this.updatedAt = Instant.now();
    }

    public void markDead() {
        this.status = Status.DEAD;
        this.updatedAt = Instant.now();
    }

    public void incrementRetry(String reason) {
        this.retryCount++;
        this.failureReason = reason;
        this.status = Status.PENDING;
        this.updatedAt = Instant.now();
    }
}
