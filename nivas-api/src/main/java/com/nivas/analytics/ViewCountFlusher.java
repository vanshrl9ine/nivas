package com.nivas.analytics;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.catalog.PropertyRepository;

/**
 * Folds Redis view deltas back into Postgres.
 *
 * <p>Runs on a schedule rather than per view, which turns what would be one row
 * lock per page load into one batched statement per interval. The counter is
 * therefore eventually consistent and occasionally slightly behind — an entirely
 * acceptable trade for a number used to rank and display, never to bill.
 */
@Component
public class ViewCountFlusher {

    private static final Logger log = LoggerFactory.getLogger(ViewCountFlusher.class);

    private final ViewCounterService viewCounter;
    private final ViewCountWriter writer;

    public ViewCountFlusher(ViewCounterService viewCounter, ViewCountWriter writer) {
        this.viewCounter = viewCounter;
        this.writer = writer;
    }

    @Scheduled(fixedDelayString = "${nivas.analytics.view-flush-interval:30000}")
    public void flush() {
        ViewCounterService.Snapshot snapshot;
        try {
            snapshot = viewCounter.drainPendingViews();
        } catch (RuntimeException ex) {
            log.warn("Could not read pending view counts from Redis", ex);
            return;
        }

        if (snapshot.isEmpty()) {
            viewCounter.discardSnapshot(snapshot);
            return;
        }

        try {
            writer.applyDeltas(snapshot.deltas());
            viewCounter.discardSnapshot(snapshot);
            log.debug("Flushed view counts for {} listings", snapshot.deltas().size());
        } catch (RuntimeException ex) {
            log.warn("Failed to persist {} view deltas; returning them to Redis for the next run",
                    snapshot.deltas().size(), ex);
            viewCounter.restoreSnapshot(snapshot);
        }
    }

    /**
     * Separate bean on purpose. {@code @Transactional} is applied by a proxy, so a
     * call from {@link #flush()} to a method on {@code this} would bypass it
     * entirely and run without a transaction.
     */
    @Component
    static class ViewCountWriter {

        private final PropertyRepository propertyRepository;

        ViewCountWriter(PropertyRepository propertyRepository) {
            this.propertyRepository = propertyRepository;
        }

        /**
         * Applied as a relative increment in SQL, not a read-modify-write. Loading
         * each entity and setting a field would cost N selects and would race with
         * any concurrent write to the same row.
         */
        @Transactional
        void applyDeltas(Map<UUID, Long> deltas) {
            deltas.forEach(propertyRepository::incrementViewCount);
        }
    }
}
