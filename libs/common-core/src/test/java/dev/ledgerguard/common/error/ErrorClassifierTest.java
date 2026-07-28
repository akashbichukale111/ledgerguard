package dev.ledgerguard.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Correctness-relevant test of error classification for the retry ladder (ADR-0012).
 *
 * <p>Misclassification in production: retryable → non-retryable discards recoverable work;
 * non-retryable → retryable delays operator discovery by 5+ minutes through the entire ladder.
 */
class ErrorClassifierTest {

    @Nested
    @DisplayName("Explicit exception types")
    class ExplicitTypes {

        @Test
        void nonRetryableExceptionClassifiedAsNonRetryable() {
            var ex = new NonRetryableException("test");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isFalse();
            assertThat(classified.reason()).contains("explicitly marked non-retryable");
        }

        @Test
        void retryableExceptionClassifiedAsRetryable() {
            var ex = new RetryableException("test");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
            assertThat(classified.reason()).contains("explicitly marked retryable");
        }
    }

    @Nested
    @DisplayName("Non-retryable: permanent failures")
    class NonRetryable {

        @Test
        void illegalArgumentExceptionIsNonRetryable() {
            var ex = new IllegalArgumentException("invalid value");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isFalse();
            assertThat(classified.reason()).contains("validation");
        }

        @Test
        void illegalStateExceptionIsNonRetryable() {
            var ex = new IllegalStateException("invalid state");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isFalse();
            assertThat(classified.reason()).contains("validation");
        }

        @Test
        void exceptionWithJsonProcessingInNameIsNonRetryable() {
            // Simulate a JSON processing exception by class name
            class JsonProcessingException extends RuntimeException {
                JsonProcessingException(String message) {
                    super(message);
                }
            }
            var ex = new JsonProcessingException("malformed");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isFalse();
            assertThat(classified.reason()).contains("deserialization");
        }
    }

    @Nested
    @DisplayName("Retryable: transient failures")
    class Retryable {

        @Test
        void sqlExceptionIsRetryable() {
            var ex = new SQLException("Connection refused");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
            assertThat(classified.reason()).contains("database");
        }

        @Test
        void connectExceptionIsRetryable() {
            var ex = new ConnectException("Connection refused");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
            assertThat(classified.reason()).contains("network");
        }

        @Test
        void socketTimeoutExceptionIsRetryable() {
            var ex = new SocketTimeoutException("Read timed out");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
            assertThat(classified.reason()).contains("network");
        }

        @Test
        void timeoutExceptionIsRetryable() {
            var ex = new java.util.concurrent.TimeoutException("Operation timeout");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
            assertThat(classified.reason()).contains("timeout");
        }

        @Test
        void exceptionWithTimeoutInMessageIsRetryable() {
            var ex = new RuntimeException("Operation timeout while connecting");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
            assertThat(classified.reason()).contains("network");
        }
    }

    @Nested
    @DisplayName("Unknown exceptions (safe default)")
    class UnknownExceptions {

        @Test
        void unknownExceptionDefaultsToRetryable() {
            var ex = new RuntimeException("Something went wrong");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable())
                    .as("unknown exceptions default to retryable (safer than discarding)")
                    .isTrue();
            assertThat(classified.reason()).contains("unknown");
        }

        @Test
        void customExceptionDefaultsToRetryable() {
            var ex = new RuntimeException("custom failure");
            var classified = ErrorClassifier.classify(ex);

            assertThat(classified.retryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("Classification is used by retry logic")
    class UsagePatterns {

        @Test
        void classificationDeterminesRoutingDecision() {
            // Non-retryable: straight to DLT, no retry ladder
            var nonRetryable = new IllegalArgumentException("bad value");
            var nrClassified = ErrorClassifier.classify(nonRetryable);
            assertThat(nrClassified.retryable()).isFalse();

            // Retryable: attempt ladder
            var retryable = new SQLException("Database down");
            var rClassified = ErrorClassifier.classify(retryable);
            assertThat(rClassified.retryable()).isTrue();

            // Based on classification, publish logic would:
            // - nonRetryable: dlt(...)
            // - retryable: retry.1, retry.2, retry.3 (with delays), then dlt if exhausted
        }
    }
}
