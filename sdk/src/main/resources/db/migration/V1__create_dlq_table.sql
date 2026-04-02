-- DLQ table: stores events that failed Kafka publish after all retries.
-- Partitioned by status so the replayer can efficiently scan PENDING rows.

CREATE TABLE IF NOT EXISTS dlq_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(36)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    -- Avro-serialized payload stored as raw bytes
    payload         BYTEA        NOT NULL,
    -- W3C traceparent for distributed trace linkage
    trace_context   VARCHAR(512),
    failure_reason  TEXT,
    retry_count     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'REPLAYING', 'REPLAYED', 'DEAD')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- For TTL re-arm: events older than 4 min need Redis TTL refreshed before replay
    original_occurred_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_dlq_event_id    ON dlq_events (event_id);
CREATE INDEX idx_dlq_status_created    ON dlq_events (status, created_at)
    WHERE status IN ('PENDING', 'REPLAYING');

-- Auto-update updated_at on every write
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dlq_updated_at
    BEFORE UPDATE ON dlq_events
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
