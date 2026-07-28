package dev.ledgerguard.common.kafka;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A message wrapped for retry ladder processing (ADR-0012).
 *
 * <p>Carries the original event envelope through retry.1, retry.2, retry.3 topics with
 * metadata about attempt count, timing, and the failure reason. Dead-letter topic entries
 * include a stack trace digest for operator debugging.
 */
public final class RetryEnvelope {
    @JsonProperty("originalEnvelope")
    private final String originalEnvelope;

    @JsonProperty("attemptCount")
    private final int attemptCount;

    @JsonProperty("firstFailedAt")
    private final Instant firstFailedAt;

    @JsonProperty("lastFailedAt")
    private final Instant lastFailedAt;

    @JsonProperty("reason")
    private final String reason;

    @JsonProperty("stackTraceDigest")
    private final String stackTraceDigest;

    public RetryEnvelope(
            String originalEnvelope,
            int attemptCount,
            Instant firstFailedAt,
            Instant lastFailedAt,
            String reason,
            String stackTraceDigest) {
        this.originalEnvelope = Objects.requireNonNull(originalEnvelope, "originalEnvelope");
        this.attemptCount = attemptCount;
        this.firstFailedAt = Objects.requireNonNull(firstFailedAt, "firstFailedAt");
        this.lastFailedAt = Objects.requireNonNull(lastFailedAt, "lastFailedAt");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.stackTraceDigest = Objects.requireNonNull(stackTraceDigest, "stackTraceDigest");
    }

    public String originalEnvelope() {
        return originalEnvelope;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Instant firstFailedAt() {
        return firstFailedAt;
    }

    public Instant lastFailedAt() {
        return lastFailedAt;
    }

    public String reason() {
        return reason;
    }

    public String stackTraceDigest() {
        return stackTraceDigest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RetryEnvelope that)) return false;
        return attemptCount == that.attemptCount
                && originalEnvelope.equals(that.originalEnvelope)
                && firstFailedAt.equals(that.firstFailedAt)
                && lastFailedAt.equals(that.lastFailedAt)
                && reason.equals(that.reason)
                && stackTraceDigest.equals(that.stackTraceDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalEnvelope, attemptCount, firstFailedAt, lastFailedAt, reason, stackTraceDigest);
    }

    @Override
    public String toString() {
        return "RetryEnvelope{"
                + "attemptCount="
                + attemptCount
                + ", reason='"
                + reason
                + '\''
                + ", firstFailedAt="
                + firstFailedAt
                + ", lastFailedAt="
                + lastFailedAt
                + '}';
    }
}
