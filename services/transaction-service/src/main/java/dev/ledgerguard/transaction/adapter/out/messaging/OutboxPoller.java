package dev.ledgerguard.transaction.adapter.out.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRecordEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRepository;

/**
 * Publishes outbox records to Kafka.
 *
 * <p>The claim query uses {@code FOR UPDATE SKIP LOCKED}, so several instances can poll
 * concurrently without blocking each other or double-claiming a row (ADR-0005).
 *
 * <h2>The failure window, stated plainly</h2>
 *
 * If this process dies <b>after</b> Kafka acknowledges but <b>before</b> the row is marked
 * published, the row is re-claimed on restart and the event is published <b>twice</b>.
 *
 * <p>That is not a bug to be engineered away — it is inherent to publishing across two systems
 * without a distributed transaction, and it is precisely why every consumer is idempotent
 * (ADR-0006). Narrowing the window is possible; closing it is not.
 *
 * <p>Records are published <b>one at a time with a synchronous ack</b> rather than batched. A batch
 * that fails halfway leaves an ambiguous set of published rows, and resolving that ambiguity costs
 * more than the throughput saved at this scale.
 *
 * <p>Constructed by TransactionServiceConfig, not component-scanned: the batch size comes from configuration and cannot be autowired as a bare int.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    private final Counter published;
    private final Counter failed;

    public OutboxPoller(
            OutboxRepository outbox, EventPublisher publisher, Clock clock, MeterRegistry meters, int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.published = Counter.builder("ledgerguard.outbox.published")
                .description("Outbox records successfully published to Kafka")
                .register(meters);
        this.failed = Counter.builder("ledgerguard.outbox.publish.failed")
                .description("Outbox publish attempts that failed and will be retried")
                .register(meters);

        // Outbox depth is the earliest signal that publishing has stalled, and a stalled poller
        // looks exactly like an idle system unless someone is watching this.
        meters.gauge("ledgerguard.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull);
    }

    /**
     * Claims and publishes one batch.
     *
     * <p>{@code REQUIRES_NEW} so a caller's transaction cannot hold the row locks open, and so a
     * failure here does not roll back anything the caller did.
     *
     * @return number of records published
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int publishBatch() {
        List<OutboxRecordEntity> batch = outbox.claimUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (OutboxRecordEntity record : batch) {
            record.recordAttempt();
            try {
                publisher.publish(record);
                record.markPublished(clock.instant());
                published.increment();
                count++;
            } catch (RuntimeException e) {
                // Leave published_at null: the row stays claimable and will be retried on the next
                // poll. Do NOT rethrow — one bad record must not roll back the rows that already
                // succeeded in this batch and were acknowledged by the broker.
                failed.increment();
                log.warn(
                        "outbox publish failed eventId={} attempts={} reason={}",
                        record.eventId(),
                        record.publishAttempts(),
                        e.toString());
            }
        }
        return count;
    }

    /** Age of the oldest unpublished record — the metric that actually detects a stalled poller. */
    public Duration oldestPendingAge() {
        return outbox.claimUnpublished(PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(r -> Duration.between(r.occurredAt(), Instant.now(clock)))
                .orElse(Duration.ZERO);
    }
}
