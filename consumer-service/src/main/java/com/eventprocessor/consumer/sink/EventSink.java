package com.eventprocessor.consumer.sink;

import com.eventprocessor.sdk.model.GitHubEvent;

/**
 * Contract for a downstream sink that processes a single {@link GitHubEvent}.
 *
 * <p>Each sink is responsible for:
 * <ul>
 *   <li>Filtering by event type if it only handles a subset.</li>
 *   <li>Recording its own Micrometer metrics.</li>
 *   <li>Being idempotent — the consumer's windowed dedup provides a best-effort
 *       guarantee but sinks must tolerate rare re-delivery.</li>
 * </ul>
 *
 * <p>Implementations are {@code @Component}s collected by
 * {@link com.eventprocessor.consumer.GitHubEventConsumer} via Spring's
 * {@code List<EventSink>} injection for automatic fan-out.
 */
public interface EventSink {

    /**
     * Human-readable sink name used in logs and metric tags.
     */
    String name();

    /**
     * Returns {@code true} if this sink wants to process the given event type.
     * Returning {@code false} causes the consumer to skip this sink for that event
     * without counting it as an error.
     */
    boolean supports(com.eventprocessor.sdk.model.EventType eventType);

    /**
     * Process the event. Must be idempotent.
     * Any unchecked exception propagates to the consumer's fan-out loop,
     * which records it as a sink failure metric and continues to the next sink.
     *
     * @param event the fully deserialized Avro event
     */
    void process(GitHubEvent event);
}
