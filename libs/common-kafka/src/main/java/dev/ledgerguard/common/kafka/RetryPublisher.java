package dev.ledgerguard.common.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.apache.kafka.clients.producer.ProducerRecord;

import dev.ledgerguard.common.error.ErrorClassifier;

/**
 * Routes failed messages to the retry ladder (retry.1/2/3) or dead-letter topic based on error
 * classification (ADR-0012).
 *
 * <p>Non-retryable errors skip the ladder and go straight to DLT for operator visibility. Retryable
 * errors move through the ladder with delays: retry.1 (5s) → retry.2 (30s) → retry.3 (5m) → dlt.
 *
 * <p>Retry envelope carries original event, attempt count, and failure reason to support replay
 * and debugging.
 */
public final class RetryPublisher {
    private static final String RETRY_1_SUFFIX = ".retry.1";
    private static final String RETRY_2_SUFFIX = ".retry.2";
    private static final String RETRY_3_SUFFIX = ".retry.3";
    private static final String DLT_SUFFIX = ".dlt";

    /**
     * Attempt number past the last rung of the ladder. Passing this as {@code attemptCount} routes a
     * message to the dead-letter topic regardless of how the error classifies.
     */
    public static final int DLT_ATTEMPT = 4;

    /**
     * Determine routing topic for a failed message.
     *
     * @param sourceTopic original topic (e.g., "transactions.events.v1")
     * @param attemptCount current attempt (1 = first failure)
     * @param isRetryable true if error is transient, false if permanent
     * @return routing topic name
     */
    public static String routingTopic(String sourceTopic, int attemptCount, boolean isRetryable) {
        Objects.requireNonNull(sourceTopic, "sourceTopic");

        if (!isRetryable) {
            return sourceTopic + DLT_SUFFIX;
        }

        return switch (attemptCount) {
            case 1 -> sourceTopic + RETRY_1_SUFFIX;
            case 2 -> sourceTopic + RETRY_2_SUFFIX;
            case 3 -> sourceTopic + RETRY_3_SUFFIX;
            default -> sourceTopic + DLT_SUFFIX; // exhausted retries
        };
    }

    /**
     * Build a ProducerRecord for a failed message, routed to retry ladder or DLT.
     *
     * @param sourceTopic original topic
     * @param partition original partition (preserved for DLT grouping)
     * @param offset original offset (preserved for audit)
     * @param originalEnvelope JSON serialized event
     * @param attemptCount current attempt
     * @param ex exception thrown during processing
     * @return ProducerRecord ready to send
     */
    public static ProducerRecord<String, String> buildRetryRecord(
            String sourceTopic, int partition, long offset, String originalEnvelope, int attemptCount, Throwable ex) {
        Objects.requireNonNull(sourceTopic, "sourceTopic");
        Objects.requireNonNull(originalEnvelope, "originalEnvelope");
        Objects.requireNonNull(ex, "ex");

        var classification = ErrorClassifier.classify(ex);
        var now = Instant.now();
        var stackDigest = digestStackTrace(ex);

        var retry = new RetryEnvelope(
                originalEnvelope,
                attemptCount,
                now, // firstFailedAt (simplified: set on first failure, update on retry)
                now, // lastFailedAt
                classification.reason(),
                stackDigest);

        String retryJson = serializeRetryEnvelope(retry);
        String topic = routingTopic(sourceTopic, attemptCount, classification.retryable());

        // Key: preserve partition grouping for DLT operator queries
        String key = String.format("%d-%d-%d", partition, offset, attemptCount);

        return new ProducerRecord<>(topic, key, retryJson);
    }

    /** Serialize RetryEnvelope to its JSON wire form. See {@link RetryEnvelopeCodec}. */
    static String serializeRetryEnvelope(RetryEnvelope retry) {
        return RetryEnvelopeCodec.toJson(retry);
    }

    /**
     * Compute SHA-256 digest of exception stack trace for DLT inspection.
     *
     * <p>Digest is base64-encoded for compact representation.
     */
    static String digestStackTrace(Throwable ex) {
        try {
            var stackTrace = ex.toString();
            var bytes = stackTrace.getBytes(StandardCharsets.UTF_8);
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(bytes);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private RetryPublisher() {}
}
