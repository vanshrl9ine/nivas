package com.nivas.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of pending events for this instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the relay safe to run on
     * every application node at once: each poller locks a disjoint set of rows
     * and the others step over them instead of blocking. Without SKIP LOCKED,
     * N instances would serialise behind one another on the same head rows.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    /**
     * Housekeeping: published events are kept briefly as an audit trail, then
     * dropped so the table does not grow without bound.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = com.nivas.outbox.OutboxStatus.PUBLISHED AND e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
