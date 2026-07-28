package dev.ledgerguard.common.error;

/**
 * Classifies exceptions as retryable or non-retryable for the retry ladder (ADR-0012).
 *
 * <p><b>This is a correctness-relevant component.</b> Misclassifying a transient error as
 * non-retryable discards recoverable work. Misclassifying a permanent error as retryable delays
 * operator discovery by 5+ minutes.
 *
 * <p>This classifier works by exception class name and message content, so it can be used in
 * common-core without service-specific dependencies. Service-specific classifiers can extend
 * this with more detailed checks.
 */
public final class ErrorClassifier {

    /**
     * Classification result: whether an exception is retryable, and the reason.
     *
     * @param retryable true if retrying may succeed
     * @param reason human-readable explanation for logs/audit
     */
    public record Classification(boolean retryable, String reason) {}

    /**
     * Classify an exception for retry ladder routing.
     *
     * <p>Explicitly classified exceptions are routed according to their classification. Unknown
     * exceptions default to retryable (the safe behavior: risking wasted work rather than
     * discarding a potentially recoverable message).
     *
     * @return Classification with retryable flag and reason
     */
    public static Classification classify(Throwable ex) {
        // Explicit non-retryable exceptions
        if (ex instanceof NonRetryableException) {
            return new Classification(false, "explicitly marked non-retryable");
        }

        String className = ex.getClass().getSimpleName();
        String message = ex.getMessage() == null ? "" : ex.getMessage();

        // Non-retryable by class name or message content
        if (className.contains("JsonProcessing") || className.contains("JsonMapping")) {
            return new Classification(false, "JSON deserialization failure (malformed payload)");
        }

        if (className.contains("DataIntegrityViolation") || className.contains("ConstraintViolation")) {
            return new Classification(false, "data integrity violation (schema mismatch or constraint breach)");
        }

        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return new Classification(false, "illegal argument or state (validation error)");
        }

        // Explicit retryable exceptions
        if (ex instanceof RetryableException) {
            return new Classification(true, "explicitly marked retryable");
        }

        // Retryable by class name or message content
        if (className.contains("SQLException") || className.contains("PSQLException")) {
            return new Classification(true, "transient database error (connection/timeout)");
        }

        if (className.contains("MongoException") || className.contains("MongoSocket")) {
            return new Classification(true, "MongoDB transient error");
        }

        if (ex instanceof java.net.ConnectException
                || ex instanceof java.net.SocketException
                || ex instanceof java.net.SocketTimeoutException
                || ex instanceof java.util.concurrent.TimeoutException) {
            return new Classification(true, "network or connection timeout");
        }

        if (className.contains("ConnectException")
                || className.contains("SocketException")
                || className.contains("TimeoutException")
                || message.contains("timeout")
                || message.contains("Connection refused")) {
            return new Classification(true, "network or connection failure");
        }

        // Default: treat unknown exceptions as retryable
        // (safer to waste work than to discard a potentially recoverable message)
        return new Classification(true, "unknown exception type, defaulting to retryable");
    }

    private ErrorClassifier() {}
}
