package dev.ledgerguard.common.error;

/**
 * A transient error where retrying may succeed.
 *
 * <p>Causes: database connection failures, timeouts, lock contention, transient I/O errors. The
 * error itself is deterministic (code), but the condition (database down, network latency) is
 * transient. Retrying is appropriate.
 *
 * <p>Published to retry ladder: retry.1 (5s) → retry.2 (30s) → retry.3 (5m) → dlt.
 */
public class RetryableException extends RuntimeException {
    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
