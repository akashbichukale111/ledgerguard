package dev.ledgerguard.common.error;

/**
 * A permanent error where retrying will fail identically, forever.
 *
 * <p>Causes: deserialization failure, schema violation, validation error, business-rule rejection.
 * The message itself is defective in a way that is not transient. Retrying is pointless and
 * delays operator discovery by the entire retry ladder (5m+). Send straight to DLT.
 *
 * <p>Published directly to dead-letter topic, no retry ladder.
 */
public class NonRetryableException extends RuntimeException {
    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
