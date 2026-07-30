package com.nivas.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * What the indexer receives when a listing changes.
 *
 * <p>Note what is <em>not</em> here: the listing itself. The event carries an id,
 * and the indexer re-reads current state from Postgres. That costs one extra read
 * but removes a whole class of bug — a payload serialised at write time can be
 * overtaken by a later change and, if the two events are processed out of order,
 * would reinstate stale data. Re-reading means the index converges on whatever
 * the database currently says, regardless of delivery order.
 *
 * <p>{@code version} and {@code status} are a snapshot for debugging and audit
 * only. Nothing branches on them: the external version Elasticsearch enforces is
 * read fresh from the row at projection time, not taken from here. They are
 * recorded because "what did the system believe when it emitted this?" is the
 * first question worth asking when the index and the database disagree.
 */
public record PropertyChangedEvent(
        UUID propertyId,
        long version,
        String status,
        ChangeKind kind,
        Instant occurredAt) {

    public enum ChangeKind {
        /** Listing is (or has become) publicly searchable — upsert into the index. */
        UPSERT,
        /** Listing left the searchable set — remove it from the index. */
        REMOVE
    }

    public static final String AGGREGATE_TYPE = "property";
    public static final String TOPIC = "nivas.property.changed";

    public static PropertyChangedEvent upsert(UUID propertyId, long version, String status) {
        return new PropertyChangedEvent(propertyId, version, status, ChangeKind.UPSERT, Instant.now());
    }

    public static PropertyChangedEvent remove(UUID propertyId, long version, String status) {
        return new PropertyChangedEvent(propertyId, version, status, ChangeKind.REMOVE, Instant.now());
    }
}
