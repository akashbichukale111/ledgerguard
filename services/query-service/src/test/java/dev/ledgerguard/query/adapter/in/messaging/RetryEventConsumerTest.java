package dev.ledgerguard.query.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import dev.ledgerguard.common.kafka.RetryEnvelope;
import dev.ledgerguard.common.kafka.RetryEnvelopeCodec;
import dev.ledgerguard.common.kafka.RetryPublisher;
import dev.ledgerguard.query.adapter.out.messaging.RetryPublishingService;
import dev.ledgerguard.query.application.ProjectionService;

/**
 * Ladder progression for the retry consumer, exercised against mocks rather than a live broker.
 *
 * <p>An earlier integration-test skeleton covered this on paper only: it required Docker, had empty
 * method bodies, and did not compile. These tests assert the behaviour that actually matters — which
 * rung a failure advances to, that the offset always moves, and that a poison message is not lost —
 * and they run in the ordinary unit-test pass.
 */
@DisplayName("RetryEventConsumer")
class RetryEventConsumerTest {

    private static final String SOURCE_TOPIC = "transactions.events.v1";

    private ProjectionService projections;
    private RetryPublishingService retryPublisher;
    private Acknowledgment acknowledgment;
    private RetryEventConsumer consumer;

    @BeforeEach
    void setUp() {
        projections = mock(ProjectionService.class);
        retryPublisher = mock(RetryPublishingService.class);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new RetryEventConsumer(projections, retryPublisher);
    }

    /** A retry message as it appears on the wire, wrapping the given event payload. */
    private static ConsumerRecord<String, String> retryRecord(String topic, String payload) {
        var envelope = new RetryEnvelope(
                payload,
                1,
                Instant.parse("2026-01-15T10:30:00Z"),
                Instant.parse("2026-01-15T10:30:00Z"),
                "projection apply failed",
                "digest");
        return new ConsumerRecord<>(topic, 0, 0L, "k", RetryEnvelopeCodec.toJson(envelope));
    }

    @Nested
    @DisplayName("ladder progression")
    class LadderProgression {

        @Test
        void retry1FailureAdvancesToAttempt2() {
            String payload = "{\"transactionId\":\"tx-1\"}";
            when(projections.apply(payload)).thenThrow(new IllegalStateException("still failing"));

            consumer.consumeRetry1(retryRecord(SOURCE_TOPIC + ".retry.1", payload), acknowledgment);

            var attempt = ArgumentCaptor.forClass(Integer.class);
            verify(retryPublisher).publishRetryOrDlt(anyString(), attempt.capture(), anyString(), any());
            assertThat(attempt.getValue()).isEqualTo(2);
        }

        @Test
        void retry2FailureAdvancesToAttempt3() {
            String payload = "{\"transactionId\":\"tx-2\"}";
            when(projections.apply(payload)).thenThrow(new IllegalStateException("still failing"));

            consumer.consumeRetry2(retryRecord(SOURCE_TOPIC + ".retry.2", payload), acknowledgment);

            var attempt = ArgumentCaptor.forClass(Integer.class);
            verify(retryPublisher).publishRetryOrDlt(anyString(), attempt.capture(), anyString(), any());
            assertThat(attempt.getValue()).isEqualTo(3);
        }

        @Test
        void retry3FailureExhaustsTheLadderAndRoutesToDlt() {
            String payload = "{\"transactionId\":\"tx-3\"}";
            when(projections.apply(payload)).thenThrow(new IllegalStateException("still failing"));

            consumer.consumeRetry3(retryRecord(SOURCE_TOPIC + ".retry.3", payload), acknowledgment);

            var attempt = ArgumentCaptor.forClass(Integer.class);
            verify(retryPublisher).publishRetryOrDlt(anyString(), attempt.capture(), anyString(), any());
            assertThat(attempt.getValue()).isEqualTo(RetryPublisher.DLT_ATTEMPT);
        }

        @Test
        void successStopsTheLadder() {
            String payload = "{\"transactionId\":\"tx-ok\"}";
            when(projections.apply(payload)).thenReturn(true);

            consumer.consumeRetry1(retryRecord(SOURCE_TOPIC + ".retry.1", payload), acknowledgment);

            verify(retryPublisher, never()).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
        }

        @Test
        void aDuplicateIsNotRepublished() {
            // apply() returning false means the projection already had this event; that is a
            // successful outcome for the ladder, not a failure to retry.
            String payload = "{\"transactionId\":\"tx-dup\"}";
            when(projections.apply(payload)).thenReturn(false);

            consumer.consumeRetry2(retryRecord(SOURCE_TOPIC + ".retry.2", payload), acknowledgment);

            verify(retryPublisher, never()).publishRetryOrDlt(anyString(), anyInt(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("payload handling")
    class PayloadHandling {

        @Test
        void unwrapsTheOriginalEventRatherThanTheRetryWrapper() {
            // Regression guard: the previous string-scanning extraction truncated the payload at
            // its first embedded quote, so the projection received a fragment.
            String payload = "{\"transactionId\":\"tx-9\",\"amount\":\"100.00\"}";
            when(projections.apply(anyString())).thenReturn(true);

            consumer.consumeRetry1(retryRecord(SOURCE_TOPIC + ".retry.1", payload), acknowledgment);

            verify(projections).apply(payload);
        }
    }

    @Nested
    @DisplayName("non-blocking guarantee")
    class NonBlocking {

        @Test
        void acknowledgesAfterSuccess() {
            when(projections.apply(anyString())).thenReturn(true);

            consumer.consumeRetry1(retryRecord(SOURCE_TOPIC + ".retry.1", "{\"a\":1}"), acknowledgment);

            verify(acknowledgment).acknowledge();
        }

        @Test
        void acknowledgesAfterFailureSoThePartitionHeadDoesNotStall() {
            when(projections.apply(anyString())).thenThrow(new IllegalStateException("boom"));

            consumer.consumeRetry1(retryRecord(SOURCE_TOPIC + ".retry.1", "{\"a\":1}"), acknowledgment);

            verify(acknowledgment).acknowledge();
        }

        @Test
        void acknowledgesEvenWhenRepublishingItselfFails() {
            when(projections.apply(anyString())).thenThrow(new IllegalStateException("boom"));
            doThrow(new IllegalStateException("broker down"))
                    .when(retryPublisher)
                    .publishRetryOrDlt(anyString(), anyInt(), anyString(), any());

            consumer.consumeRetry1(retryRecord(SOURCE_TOPIC + ".retry.1", "{\"a\":1}"), acknowledgment);

            verify(acknowledgment).acknowledge();
        }
    }

    @Nested
    @DisplayName("poison messages")
    class PoisonMessages {

        @Test
        void anUnparseableRetryMessageGoesToDltRatherThanBeingDropped() {
            var record = new ConsumerRecord<>(SOURCE_TOPIC + ".retry.1", 0, 0L, "k", "not json at all");

            consumer.consumeRetry1(record, acknowledgment);

            var attempt = ArgumentCaptor.forClass(Integer.class);
            verify(retryPublisher).publishRetryOrDlt(anyString(), attempt.capture(), anyString(), any());
            assertThat(attempt.getValue()).isEqualTo(RetryPublisher.DLT_ATTEMPT);
            verify(acknowledgment, times(1)).acknowledge();
        }

        @Test
        void theRawPayloadIsPreservedOnTheDltHop() {
            var record = new ConsumerRecord<>(SOURCE_TOPIC + ".retry.1", 0, 0L, "k", "{\"truncated\":");

            consumer.consumeRetry1(record, acknowledgment);

            var body = ArgumentCaptor.forClass(String.class);
            verify(retryPublisher).publishRetryOrDlt(body.capture(), anyInt(), anyString(), any());
            assertThat(body.getValue()).isEqualTo("{\"truncated\":");
        }
    }
}
