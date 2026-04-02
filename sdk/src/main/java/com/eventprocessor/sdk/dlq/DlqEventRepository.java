package com.eventprocessor.sdk.dlq;

import com.eventprocessor.sdk.model.DlqEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DlqEventRepository extends JpaRepository<DlqEvent, Long> {

    /**
     * Fetches the oldest PENDING events up to {@code limit}, ordered by
     * created_at ASC so the replayer processes them FIFO.
     */
    @Query("""
        SELECT d FROM DlqEvent d
        WHERE d.status = 'PENDING'
        ORDER BY d.createdAt ASC
        LIMIT :limit
        """)
    List<DlqEvent> findOldestPending(@Param("limit") int limit);

    /**
     * Resets any REPLAYING rows back to PENDING on startup, in case the
     * process crashed mid-batch.
     */
    @Modifying
    @Query("UPDATE DlqEvent d SET d.status = 'PENDING' WHERE d.status = 'REPLAYING'")
    int resetStuckReplaying();

    boolean existsByEventId(String eventId);
}
