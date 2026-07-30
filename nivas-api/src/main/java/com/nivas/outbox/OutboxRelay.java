package com.nivas.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Drains the outbox into Kafka.
 *
 * <p>Delivery is at-least-once: an event can be published and then fail to be
 * marked, in which case it is republished on the next tick. Consumers are
 * written to tolerate that (see {@link com.nivas.indexing.PropertyIndexer}).
 *
 * <p>The publish happens while the claiming transaction still holds its row
 * locks. That is deliberate — releasing the lock first would let a second
 * instance claim and republish the same row — and it is why the batch is kept
 * small and the send has a hard timeout.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxRelay(OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.publishedCounter = Counter.builder("nivas.outbox.published")
                .description("Outbox events successfully published to Kafka")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("nivas.outbox.failed")
                .description("Outbox publish attempts that threw")
                .register(meterRegistry);
        // Backlog is the number worth alerting on: it stays near zero when the
        // relay is healthy and climbs immediately when Kafka is unreachable.
        meterRegistry.gauge("nivas.outbox.backlog", repository,
                repo -> repo.countByStatus(OutboxStatus.PENDING));
    }

    @Scheduled(fixedDelayString = "${nivas.outbox.poll-interval:1000}")
    @Transactional
    public void drain() {
        List<OutboxEvent> batch = repository.claimPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                // Keying by aggregate id puts every event for one listing on the
                // same partition, so per-listing ordering is preserved.
                kafkaTemplate.send(PropertyChangedEvent.TOPIC, event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished();
                publishedCounter.increment();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Outbox drain interrupted; leaving event {} pending", event.getId());
                return;
            } catch (Exception ex) {
                failedCounter.increment();
                event.markAttemptFailed(ex.toString(), MAX_ATTEMPTS);
                if (event.getStatus() == OutboxStatus.FAILED) {
                    log.error("Outbox event {} exhausted {} attempts and was parked as FAILED",
                            event.getId(), MAX_ATTEMPTS, ex);
                } else {
                    log.warn("Outbox event {} failed on attempt {}; will retry",
                            event.getId(), event.getAttempts(), ex);
                }
            }
        }
        repository.saveAll(batch);
    }

    /** Trims the published backlog so the hot partial index stays small. */
    @Scheduled(cron = "${nivas.outbox.cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void purgePublished() {
        int deleted = repository.deletePublishedBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("Purged {} published outbox events older than {} days", deleted, RETENTION.toDays());
        }
    }
}
