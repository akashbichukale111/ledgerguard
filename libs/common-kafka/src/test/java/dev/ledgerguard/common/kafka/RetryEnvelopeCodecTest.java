package dev.ledgerguard.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RetryEnvelopeCodec")
class RetryEnvelopeCodecTest {

    private static RetryEnvelope envelopeWrapping(String payload) {
        return new RetryEnvelope(
                payload,
                2,
                Instant.parse("2026-01-15T10:30:00Z"),
                Instant.parse("2026-01-15T10:31:00Z"),
                "projection apply failed",
                "abc123digest");
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void preservesAJsonPayload() throws IOException {
            // The realistic case: the thing being retried is itself a JSON event envelope, so the
            // payload is full of quotes. The previous hand-rolled reader truncated here.
            String payload = "{\"transactionId\":\"tx-123\",\"amount\":\"100.00\"}";

            RetryEnvelope parsed = RetryEnvelopeCodec.fromJson(RetryEnvelopeCodec.toJson(envelopeWrapping(payload)));

            assertThat(parsed.originalEnvelope()).isEqualTo(payload);
        }

        @Test
        void preservesBackslashes() throws IOException {
            // The previous writer escaped quotes but not backslashes, emitting invalid JSON.
            String payload = "{\"path\":\"C:\\\\ledger\\\\in\"}";

            RetryEnvelope parsed = RetryEnvelopeCodec.fromJson(RetryEnvelopeCodec.toJson(envelopeWrapping(payload)));

            assertThat(parsed.originalEnvelope()).isEqualTo(payload);
        }

        @Test
        void preservesNewlinesAndUnicode() throws IOException {
            String payload = "{\"note\":\"line one\nline two — café\"}";

            RetryEnvelope parsed = RetryEnvelopeCodec.fromJson(RetryEnvelopeCodec.toJson(envelopeWrapping(payload)));

            assertThat(parsed.originalEnvelope()).isEqualTo(payload);
        }

        @Test
        void preservesEveryField() throws IOException {
            RetryEnvelope original = envelopeWrapping("{\"a\":1}");

            assertThat(RetryEnvelopeCodec.fromJson(RetryEnvelopeCodec.toJson(original)))
                    .isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("originalEnvelopeOf")
    class OriginalEnvelopeExtraction {

        @Test
        void returnsTheWholePayload() throws IOException {
            String payload = "{\"transactionId\":\"tx-1\",\"nested\":{\"k\":\"v\"}}";

            String extracted =
                    RetryEnvelopeCodec.originalEnvelopeOf(RetryEnvelopeCodec.toJson(envelopeWrapping(payload)));

            assertThat(extracted).isEqualTo(payload);
        }

        @Test
        void rejectsAMessageMissingTheField() {
            assertThatThrownBy(() -> RetryEnvelopeCodec.originalEnvelopeOf("{\"attemptCount\":1}"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("originalEnvelope");
        }

        @Test
        void rejectsMalformedJsonRatherThanReturningGarbage() {
            // The old string-scanning reader returned a nonsense substring for input like this.
            assertThatThrownBy(() -> RetryEnvelopeCodec.originalEnvelopeOf("not json at all"))
                    .isInstanceOf(IOException.class);
        }
    }
}
