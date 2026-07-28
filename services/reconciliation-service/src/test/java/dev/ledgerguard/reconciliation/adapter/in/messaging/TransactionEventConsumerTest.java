package dev.ledgerguard.reconciliation.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.reconciliation.adapter.out.messaging.ReconciliationEventPublisher;
import dev.ledgerguard.reconciliation.adapter.out.persistence.ProcessedEventEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.ProcessedEventRepository;
import dev.ledgerguard.reconciliation.application.ReconcileTransactionHandler;
import dev.ledgerguard.reconciliation.domain.matching.BusinessCalendar;
import dev.ledgerguard.reconciliation.domain.matching.MatchingConfig;
import dev.ledgerguard.reconciliation.domain.matching.ReconciliationEngine;
import dev.ledgerguard.reconciliation.domain.matching.TransactionReconciledPayload;

/**
 * The inbound half of the reconciliation flow.
 *
 * <p>Before this consumer existed, reconciliation-service had no inbound adapter at all — the
 * engine and saga were real code nothing ever invoked, so no transaction was ever reconciled.
 */
@DisplayName("TransactionEventConsumer")
class TransactionEventConsumerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final String TX = "11111111-1111-7111-8111-111111111111";
    private static final UUID EVENT_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID CORRELATION = UUID.fromString("22222222-2222-7222-8222-222222222222");

    private ReconciliationEventPublisher publisher;
    private ProcessedEventRepository processedEvents;
    private Acknowledgment acknowledgment;
    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        publisher = mock(ReconciliationEventPublisher.class);
        processedEvents = mock(ProcessedEventRepository.class);
        acknowledgment = mock(Acknowledgment.class);
        when(processedEvents.existsById(any())).thenReturn(false);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var engine = new ReconciliationEngine(
                MatchingConfig.defaultConfig(CurrencyCode.of("USD")), BusinessCalendar.weekendsOnly());

        consumer = new TransactionEventConsumer(
                new ReconcileTransactionHandler(engine, clock), publisher, processedEvents, new ObjectMapper(), clock);
    }

    private static String quote(String json) {
        try {
            return new ObjectMapper().writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConsumerRecord<String, String> recordOf(String eventType, UUID eventId) {
        String payload =
                """
                {"transactionId":"%s","reference":"REF-1","amount":"100.00","currency":"USD",\
                "valueDate":"2026-01-15","counterpartyId":"cp-1","direction":"DEBIT"}"""
                        .formatted(TX);
        String envelope =
                """
                {"eventId":"%s","eventType":"%s","eventVersion":2,"aggregateType":"Transaction",\
                "aggregateId":"%s","sequenceNumber":0,"occurredAt":"2026-01-15T12:00:00Z",\
                "correlationId":"%s","payload":%s}"""
                        .formatted(eventId, eventType, TX, CORRELATION, quote(payload));
        return new ConsumerRecord<>("transactions.events.v1", 0, 0L, TX, envelope);
    }

    private TransactionReconciledPayload publishedOutcome() {
        ArgumentCaptor<TransactionReconciledPayload> captor =
                ArgumentCaptor.forClass(TransactionReconciledPayload.class);
        verify(publisher).publish(captor.capture(), any(), any(), any());
        return captor.getValue();
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        void publishesAnOutcomeForAReceivedTransaction() {
            consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment);

            assertThat(publishedOutcome().transactionId()).isEqualTo(UUID.fromString(TX));
        }

        @Test
        void withNoCounterpartyFeedTheHonestOutcomeIsUnmatched() {
            // No statement feed exists in this repository, so there is nothing to match against.
            // UNMATCHED is the truthful answer; inventing a match would be worse than reporting none.
            consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment);

            assertThat(publishedOutcome().outcome().name()).isEqualTo("UNMATCHED");
        }

        @Test
        void recordsDedupeAndAcknowledges() {
            consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment);

            verify(processedEvents).save(any(ProcessedEventEntity.class));
            verify(acknowledgment).acknowledge();
        }

        @Test
        void causationLinksBackToTheReceivedEvent() {
            consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment);

            ArgumentCaptor<UUID> correlation = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<UUID> causation = ArgumentCaptor.forClass(UUID.class);
            verify(publisher).publish(any(), correlation.capture(), causation.capture(), any());

            assertThat(correlation.getValue()).isEqualTo(CORRELATION);
            assertThat(causation.getValue()).isEqualTo(EVENT_ID);
        }
    }

    @Nested
    @DisplayName("delivery guarantees")
    class DeliveryGuarantees {

        @Test
        void aDuplicateIsSkippedWithoutPublishingTwice() {
            when(processedEvents.existsById(any())).thenReturn(true);

            consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment);

            verify(publisher, never()).publish(any(), any(), any(), any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        void anUnrelatedEventTypeIsIgnoredAndAcknowledged() {
            consumer.consume(recordOf("SomeOtherEvent", EVENT_ID), acknowledgment);

            verify(publisher, never()).publish(any(), any(), any(), any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        void aFailedPublishIsNotAcknowledgedSoTheRecordIsRedelivered() {
            // Acking on failure would advance the offset past a transaction that was never
            // reconciled, leaving the read model stuck at RECEIVED with no trace of why.
            doThrow(new IllegalStateException("broker down")).when(publisher).publish(any(), any(), any(), any());

            assertThatThrownBy(() -> consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment))
                    .isInstanceOf(IllegalStateException.class);

            verify(acknowledgment, never()).acknowledge();
            verify(processedEvents, never()).save(any());
        }

        @Test
        void aMalformedEnvelopeIsNotAcknowledgedEither() {
            var malformed = new ConsumerRecord<>("transactions.events.v1", 0, 0L, TX, "not json");

            assertThatThrownBy(() -> consumer.consume(malformed, acknowledgment))
                    .isInstanceOf(RuntimeException.class);

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        void dedupeIsRecordedOnlyAfterTheOutcomeIsPublished() {
            // Saving dedupe first would mean a crash between the two silently drops the transaction:
            // the redelivery would be treated as already handled.
            var order = org.mockito.Mockito.inOrder(publisher, processedEvents);

            consumer.consume(recordOf("TransactionReceived", EVENT_ID), acknowledgment);

            order.verify(publisher).publish(any(), any(), any(), any());
            order.verify(processedEvents).save(any());
        }
    }
}
