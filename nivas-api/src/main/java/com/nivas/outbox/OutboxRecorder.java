package com.nivas.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends events to the outbox from inside the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} is the safety rail: recording an event outside
 * a transaction would commit it independently of the change it describes, which
 * is precisely the dual-write problem the outbox exists to prevent. Calling this
 * without an active transaction is a programming error and fails immediately.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPropertyChange(PropertyChangedEvent event) {
        repository.save(new OutboxEvent(
                PropertyChangedEvent.AGGREGATE_TYPE,
                event.propertyId().toString(),
                event.kind().name(),
                serialise(event)));
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            // Serialising a record of primitives cannot realistically fail; if it
            // does, failing the whole transaction is the right call — an outbox
            // row we cannot write means a search update we would never make.
            throw new IllegalStateException("Could not serialise outbox payload", ex);
        }
    }
}
