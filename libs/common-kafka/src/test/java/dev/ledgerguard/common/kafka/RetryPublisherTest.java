package dev.ledgerguard.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RetryPublisher")
class RetryPublisherTest {

    private static final String SOURCE_TOPIC = "transactions.events.v1";

    @Nested
    @DisplayName("Routing decision")
    class Routing {

        @Test
        void nonRetryableGoesToDltImmediately() {
            var illegalArg = new IllegalArgumentException("invalid value");
            var topic = RetryPublisher.routingTopic(SOURCE_TOPIC, 1, false);

            assertThat(topic).isEqualTo("transactions.events.v1.dlt");
        }

        @Test
        void firstAttemptRetryableGoesToRetry1() {
            var topic = RetryPublisher.routingTopic(SOURCE_TOPIC, 1, true);
            assertThat(topic).isEqualTo("transactions.events.v1.retry.1");
        }

        @Test
        void secondAttemptRetryableGoesToRetry2() {
            var topic = RetryPublisher.routingTopic(SOURCE_TOPIC, 2, true);
            assertThat(topic).isEqualTo("transactions.events.v1.retry.2");
        }

        @Test
        void thirdAttemptRetryableGoesToRetry3() {
            var topic = RetryPublisher.routingTopic(SOURCE_TOPIC, 3, true);
            assertThat(topic).isEqualTo("transactions.events.v1.retry.3");
        }

        @Test
        void exhaustedRetriesGoToDlt() {
            var topic = RetryPublisher.routingTopic(SOURCE_TOPIC, 4, true);
            assertThat(topic).isEqualTo("transactions.events.v1.dlt");
        }
    }

    @Nested
    @DisplayName("Record building")
    class RecordBuilding {

        @Test
        void buildsRetryRecordWithKeyForPartitionTracking() {
            var originalEnvelope = "{\"transactionId\":\"tx123\"}";
            var ex = new SQLException("Connection refused");

            var record = RetryPublisher.buildRetryRecord(SOURCE_TOPIC, 0, 100L, originalEnvelope, 1, ex);

            assertThat(record.topic()).isEqualTo("transactions.events.v1.retry.1");
            assertThat(record.key()).isEqualTo("0-100-1");
            assertThat(record.value()).contains("\"originalEnvelope\"");
            assertThat(record.value()).contains("\"attemptCount\":1");
        }

        @Test
        void recordIncludesFailureReason() {
            var originalEnvelope = "{}";
            var ex = new SQLException("Timeout");

            var record = RetryPublisher.buildRetryRecord(SOURCE_TOPIC, 1, 50L, originalEnvelope, 1, ex);

            assertThat(record.value()).contains("\"reason\"");
            assertThat(record.value()).contains("database");
        }

        @Test
        void recordIncludesStackTraceDigest() {
            var originalEnvelope = "{}";
            var ex = new SQLException("Error");

            var record = RetryPublisher.buildRetryRecord(SOURCE_TOPIC, 0, 0L, originalEnvelope, 1, ex);

            assertThat(record.value()).contains("\"stackTraceDigest\":");
        }
    }

    @Nested
    @DisplayName("Stack trace digest")
    class StackTraceDigest {

        @Test
        void digestIsBase64Encoded() {
            var ex = new RuntimeException("test error");
            var digest = RetryPublisher.digestStackTrace(ex);

            assertThat(digest)
                    .as("digest should be base64-like (alphanumeric + /+=)")
                    .matches("^[A-Za-z0-9/+]*={0,2}$");
        }

        @Test
        void digestIsDeterministic() {
            var ex = new RuntimeException("test error");
            var digest1 = RetryPublisher.digestStackTrace(ex);
            var digest2 = RetryPublisher.digestStackTrace(ex);

            assertThat(digest1).isEqualTo(digest2);
        }

        @Test
        void differentExceptionsDifferentDigests() {
            var ex1 = new RuntimeException("error 1");
            var ex2 = new RuntimeException("error 2");

            var digest1 = RetryPublisher.digestStackTrace(ex1);
            var digest2 = RetryPublisher.digestStackTrace(ex2);

            assertThat(digest1).isNotEqualTo(digest2);
        }
    }

    @Nested
    @DisplayName("Envelope serialization")
    class Serialization {

        @Test
        void serializesEnvelopeToJson() {
            var envelope = new RetryEnvelope(
                    "{\"eventId\":\"123\"}",
                    1,
                    java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    java.time.Instant.parse("2026-01-01T00:00:05Z"),
                    "transient error",
                    "base64digest");

            var json = RetryPublisher.serializeRetryEnvelope(envelope);

            assertThat(json)
                    .contains("\"originalEnvelope\"")
                    .contains("\"attemptCount\":1")
                    .contains("\"reason\"")
                    .contains("\"stackTraceDigest\"");
        }

        @Test
        void escapesQuotesInJson() {
            var envelope = new RetryEnvelope(
                    "{\"quote\":\"value\"}", 1, java.time.Instant.now(), java.time.Instant.now(), "reason", "digest");

            var json = RetryPublisher.serializeRetryEnvelope(envelope);

            // JSON should have escaped quotes
            assertThat(json).contains("\\\"");
        }
    }
}
