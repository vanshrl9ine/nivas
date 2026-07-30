package com.nivas.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.nivas.outbox.PropertyChangedEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes listing-change events and projects them into Elasticsearch.
 *
 * <p>Acknowledgement is manual and happens only after the index write succeeds,
 * so a crash mid-projection replays the event rather than losing it. Redelivery
 * is safe because {@link PropertyIndexService#syncFromDatabase} re-reads current
 * state and Elasticsearch rejects out-of-order versions.
 */
@Component
public class PropertyIndexer {

    private static final Logger log = LoggerFactory.getLogger(PropertyIndexer.class);

    private final PropertyIndexService indexService;
    private final ObjectMapper objectMapper;
    private final Counter indexedCounter;
    private final Counter skippedCounter;
    private final Timer projectionTimer;

    public PropertyIndexer(PropertyIndexService indexService, ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.indexService = indexService;
        this.objectMapper = objectMapper;
        this.indexedCounter = Counter.builder("nivas.indexer.processed")
                .description("Property change events projected into Elasticsearch")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder("nivas.indexer.skipped")
                .description("Events discarded as unprocessable")
                .register(meterRegistry);
        this.projectionTimer = Timer.builder("nivas.indexer.projection")
                .description("Time to project one change event")
                .register(meterRegistry);
    }

    @KafkaListener(topics = PropertyChangedEvent.TOPIC, groupId = "nivas-indexer")
    public void onPropertyChanged(String payload, Acknowledgment acknowledgment) {
        PropertyChangedEvent event;
        try {
            event = objectMapper.readValue(payload, PropertyChangedEvent.class);
        } catch (Exception ex) {
            // Unparseable payload. Retrying cannot help, and not acknowledging
            // would block the partition forever, so drop it loudly.
            log.error("Discarding malformed property event: {}", payload, ex);
            skippedCounter.increment();
            acknowledgment.acknowledge();
            return;
        }

        try {
            projectionTimer.recordCallable(() -> {
                switch (event.kind()) {
                    case UPSERT -> indexService.syncFromDatabase(event.propertyId());
                    case REMOVE -> indexService.deleteFromIndex(event.propertyId());
                }
                return null;
            });
            indexedCounter.increment();
            acknowledgment.acknowledge();

        } catch (Exception ex) {
            // Deliberately not acknowledged: Elasticsearch or Postgres is
            // unavailable, and the event must be redelivered once they recover.
            log.error("Failed to project listing {}; leaving the offset uncommitted",
                    event.propertyId(), ex);
            throw new IllegalStateException("Projection failed for " + event.propertyId(), ex);
        }
    }
}
