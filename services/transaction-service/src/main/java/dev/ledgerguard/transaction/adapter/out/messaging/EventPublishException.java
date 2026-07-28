package dev.ledgerguard.transaction.adapter.out.messaging;

/**
 * A publish attempt failed.
 *
 * <p>Always retryable: the outbox row keeps {@code published_at} null and is re-claimed on the next
 * poll. The system degrades — events queue up and drain when the broker returns — rather than
 * losing data.
 */
public class EventPublishException extends RuntimeException {
    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
