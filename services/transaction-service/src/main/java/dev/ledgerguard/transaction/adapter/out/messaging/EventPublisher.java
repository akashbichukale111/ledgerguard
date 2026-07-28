package dev.ledgerguard.transaction.adapter.out.messaging;

import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRecordEntity;

/**
 * Publishes one outbox record and does not return until the broker has acknowledged it.
 *
 * <p>Synchronous by design. A fire-and-forget send would let the poller mark a record published
 * before the broker had it, which converts at-least-once delivery into at-most-once and silently
 * loses events on broker failure.
 */
public interface EventPublisher {
    void publish(OutboxRecordEntity record);
}
