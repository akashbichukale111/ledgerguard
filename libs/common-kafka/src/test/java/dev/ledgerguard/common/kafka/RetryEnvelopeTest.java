package dev.ledgerguard.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RetryEnvelope")
class RetryEnvelopeTest {

    @Test
    void createsEnvelopeWithAllFields() {
        var now = Instant.now();
        var envelope = new RetryEnvelope(
                "{\"eventId\":\"123\"}", 2, now, now.plusSeconds(30), "transient database error", "SHA256=abc123...");

        assertThat(envelope.originalEnvelope()).isEqualTo("{\"eventId\":\"123\"}");
        assertThat(envelope.attemptCount()).isEqualTo(2);
        assertThat(envelope.firstFailedAt()).isEqualTo(now);
        assertThat(envelope.lastFailedAt()).isEqualTo(now.plusSeconds(30));
        assertThat(envelope.reason()).isEqualTo("transient database error");
        assertThat(envelope.stackTraceDigest()).isEqualTo("SHA256=abc123...");
    }

    @Test
    void throwsNullPointerExceptionForNullOriginalEnvelope() {
        var now = Instant.now();
        assertThatThrownBy(() -> new RetryEnvelope(null, 1, now, now, "reason", "digest"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("originalEnvelope");
    }

    @Test
    void throwsNullPointerExceptionForNullReason() {
        var now = Instant.now();
        assertThatThrownBy(() -> new RetryEnvelope("{}", 1, now, now, null, "digest"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void equalsAndHashCodeConsiderAllFields() {
        var now = Instant.now();
        var env1 = new RetryEnvelope("{}", 1, now, now, "reason", "digest");
        var env2 = new RetryEnvelope("{}", 1, now, now, "reason", "digest");
        var env3 = new RetryEnvelope("{}", 2, now, now, "reason", "digest");

        assertThat(env1).isEqualTo(env2).hasSameHashCodeAs(env2);
        assertThat(env1).isNotEqualTo(env3);
    }

    @Test
    void toStringIncludesAttemptCountAndReason() {
        var now = Instant.now();
        var envelope = new RetryEnvelope("{}", 3, now, now, "connection timeout", "digest");

        assertThat(envelope.toString()).contains("attemptCount=3").contains("reason='connection timeout'");
    }
}
