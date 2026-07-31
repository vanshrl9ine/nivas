package com.nivas.billing;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.billing.domain.PaymentEvent;

/**
 * Records an incoming webhook, in its own transaction.
 *
 * <p>The separate transaction is the whole reason this class exists. The
 * idempotency guarantee is a unique constraint on {@code (gateway, event_id)},
 * and a redelivered webhook is expected traffic rather than an error — so the
 * violation has to be caught and turned into "already seen".
 *
 * <p>Catching it inside the caller's transaction does not work. Once Postgres
 * rejects a statement the transaction is aborted, and every subsequent query on
 * it fails too; Spring marks it rollback-only and the handler returns 500 while
 * appearing, in the code, to have handled the duplicate perfectly well. Observed
 * exactly that: replaying a webhook produced a 500 rather than a quiet no-op.
 *
 * <p>{@link Propagation#REQUIRES_NEW} gives the insert its own transaction, so a
 * conflict rolls back only that, and the caller carries on undamaged.
 */
@Component
public class PaymentEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventRecorder.class);

    private final PaymentEventRepository eventRepository;

    public PaymentEventRecorder(PaymentEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * @return the stored event, or empty when this webhook has already been seen
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentEvent> recordIfNew(String gateway, String eventId, String eventType,
            String rawBody, boolean signatureValid) {

        // Fast path. Not the guarantee — two concurrent redeliveries would both
        // pass this — but it avoids provoking a constraint violation for the
        // overwhelmingly common sequential replay.
        if (eventRepository.existsByGatewayAndEventId(gateway, eventId)) {
            return Optional.empty();
        }

        try {
            return Optional.of(eventRepository.saveAndFlush(
                    new PaymentEvent(gateway, eventId, eventType, rawBody, signatureValid)));

        } catch (DataIntegrityViolationException ex) {
            // Lost the race with a concurrent delivery of the same event. The
            // other one is fulfilling it; this call must not.
            log.debug("Webhook {} was recorded concurrently; treating as a replay", eventId);
            return Optional.empty();
        }
    }

    /** Updates an event after the caller has decided what happened to it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(PaymentEvent event) {
        eventRepository.save(event);
    }
}
