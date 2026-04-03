package com.eventprocessor.consumer;

import com.eventprocessor.consumer.dedup.WindowedDeduplicator;
import com.eventprocessor.consumer.sink.EventSink;
import com.eventprocessor.sdk.model.EventType;
import com.eventprocessor.sdk.model.GitHubEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubEventConsumerTest {

    @Mock WindowedDeduplicator deduplicator;
    @Mock Acknowledgment ack;

    // Two mock sinks so we can verify fan-out isolation
    @Mock EventSink sinkA;
    @Mock EventSink sinkB;

    GitHubEventConsumer consumer;
    GitHubEvent event;
    byte[] avroBytes;

    @BeforeEach
    void setUp() throws IOException {
        consumer = new GitHubEventConsumer(deduplicator, List.of(sinkA, sinkB),
                new SimpleMeterRegistry());

        event = GitHubEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(EventType.PUSH_EVENT)
                .setRepoId(1L)
                .setRepoName("test/repo")
                .setActorLogin("user")
                .setOccurredAt(java.time.Instant.now())
                .setPayload(Map.of("ref", "refs/heads/main", "commits", "2"))
                .setTraceId(null)
                .setSchemaVersion(1)
                .build();

        avroBytes = serialize(event);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void consume_newEvent_fansOutToAllSinks() {
        when(deduplicator.isNew(event.getEventId())).thenReturn(true);
        when(sinkA.supports(any())).thenReturn(true);
        when(sinkB.supports(any())).thenReturn(true);

        consumer.consume(record(avroBytes), ack);

        verify(sinkA).process(any());
        verify(sinkB).process(any());
        verify(ack).acknowledge();
    }

    @Test
    void consume_duplicate_dropsAndAcks_noSinkCalls() {
        when(deduplicator.isNew(event.getEventId())).thenReturn(false);

        consumer.consume(record(avroBytes), ack);

        verifyNoInteractions(sinkA, sinkB);
        verify(ack).acknowledge();     // must ack or offset stalls
    }

    // ── Sink isolation ────────────────────────────────────────────────────────

    @Test
    void consume_sinkAThrows_sinkBStillRuns() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(sinkA.name()).thenReturn("sinkA");
        when(sinkA.supports(any())).thenReturn(true);
        when(sinkB.supports(any())).thenReturn(true);
        doThrow(new RuntimeException("sinkA exploded")).when(sinkA).process(any());

        consumer.consume(record(avroBytes), ack);

        verify(sinkB).process(any());     // B must still execute
        verify(ack).acknowledge();        // offset still committed
    }

    @Test
    void consume_bothSinksThrow_offsetStillCommitted() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(sinkA.name()).thenReturn("sinkA");
        when(sinkB.name()).thenReturn("sinkB");
        when(sinkA.supports(any())).thenReturn(true);
        when(sinkB.supports(any())).thenReturn(true);
        doThrow(new RuntimeException("A down")).when(sinkA).process(any());
        doThrow(new RuntimeException("B down")).when(sinkB).process(any());

        consumer.consume(record(avroBytes), ack);

        verify(ack).acknowledge();
    }

    // ── supports() filtering ─────────────────────────────────────────────────

    @Test
    void consume_sinkDoesNotSupportType_sinkSkipped() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(sinkB.supports(any())).thenReturn(true);
        when(sinkA.supports(EventType.PUSH_EVENT)).thenReturn(false);

        consumer.consume(record(avroBytes), ack);

        verify(sinkA, never()).process(any());
        verify(sinkB).process(any());
    }

    // ── Traceparent header extraction ─────────────────────────────────────────

    @Test
    void consume_withValidTraceparentHeader_processesNormally() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(sinkA.supports(any())).thenReturn(true);
        when(sinkB.supports(any())).thenReturn(true);

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        RecordHeaders headers = new RecordHeaders();
        headers.add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));

        consumer.consume(record(avroBytes, headers), ack);

        verify(sinkA).process(any());
        verify(ack).acknowledge();
    }

    @Test
    void consume_withMalformedTraceparentHeader_degradesGracefully() {
        when(deduplicator.isNew(any())).thenReturn(true);
        when(sinkA.supports(any())).thenReturn(true);
        when(sinkB.supports(any())).thenReturn(true);

        RecordHeaders headers = new RecordHeaders();
        headers.add("traceparent", "not-a-valid-traceparent".getBytes(StandardCharsets.UTF_8));

        // Must not throw — span linking is best-effort
        consumer.consume(record(avroBytes, headers), ack);

        verify(sinkA).process(any());
        verify(ack).acknowledge();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ConsumerRecord<String, byte[]> record(byte[] value) {
        return record(value, new RecordHeaders());
    }

    private ConsumerRecord<String, byte[]> record(byte[] value, RecordHeaders headers) {
        return new ConsumerRecord<>("github-events", 0, 0L,
                ConsumerRecord.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
                ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE,
                event.getEventId(), value, headers, Optional.empty());
    }

    private byte[] serialize(GitHubEvent e) throws IOException {
        var writer = new SpecificDatumWriter<>(GitHubEvent.class);
        try (var out = new ByteArrayOutputStream()) {
            var encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(e, encoder);
            encoder.flush();
            return out.toByteArray();
        }
    }
}
