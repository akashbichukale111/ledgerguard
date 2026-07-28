package dev.ledgerguard.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.ledgerguard.query.adapter.out.mongo.ProcessedEventProjectionRepository;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;

/**
 * Projection of the {@code TransactionReconciled} outcome.
 *
 * <p>This is the behaviour that makes match rate and error rate computable at all. Before this
 * event existed the projection only ever wrote {@code RECEIVED}, so no transaction reached a
 * terminal state and both rates had an empty denominator.
 */
@DisplayName("ProjectionService — TransactionReconciled")
class ProjectionServiceReconciledTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:05Z");
    private static final Instant OCCURRED = Instant.parse("2026-01-15T12:00:00Z");
    private static final String TX = "11111111-1111-7111-8111-111111111111";
    private static final UUID CORRELATION = UUID.fromString("22222222-2222-7222-8222-222222222222");

    private Transaction360Repository transactions;
    private ProcessedEventProjectionRepository processedEvents;
    private ProjectionService service;

    @BeforeEach
    void setUp() {
        transactions = mock(Transaction360Repository.class);
        processedEvents = mock(ProcessedEventProjectionRepository.class);
        when(processedEvents.existsById(anyString())).thenReturn(false);

        service = new ProjectionService(
                transactions,
                processedEvents,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    /** A transaction already projected from TransactionReceived, sitting at RECEIVED. */
    private Transaction360Document existingReceived() {
        var doc = new Transaction360Document(TX);
        doc.setStatus("RECEIVED");
        doc.setOccurredAt(OCCURRED);
        doc.setUpdatedAt(OCCURRED);
        doc.setCorrelationId(CORRELATION);
        doc.recordSequence(0L);
        return doc;
    }

    private String reconciledEnvelope(String outcome, String classification) {
        String payload =
                """
                {"transactionId":"%s","outcome":"%s","classification":"%s","matchedEntryIds":["ext-1"],\
                "ruleId":"rule-exact","ruleSetVersion":"v1","candidatePoolSize":4,\
                "reconciledAt":"2026-01-15T12:00:00Z"}"""
                        .formatted(TX, outcome, classification);
        return """
            {"eventId":"%s","eventType":"TransactionReconciled","eventVersion":1,\
            "aggregateType":"Transaction","aggregateId":"%s","sequenceNumber":1,\
            "occurredAt":"2026-01-15T12:00:00Z","correlationId":"%s","payload":%s}"""
                .formatted(UUID.randomUUID(), TX, CORRELATION, quote(payload));
    }

    /** The envelope carries payload as a JSON string, not a nested object. */
    private static String quote(String json) {
        try {
            return new ObjectMapper().writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Transaction360Document applyAndCapture(String envelope) {
        when(transactions.findById(TX)).thenReturn(Optional.of(existingReceived()));

        boolean changed = service.apply(envelope);
        assertThat(changed).isTrue();

        ArgumentCaptor<Transaction360Document> captor = ArgumentCaptor.forClass(Transaction360Document.class);
        verify(transactions).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aMatchedOutcomeMovesTheTransactionOffReceived() {
        Transaction360Document saved = applyAndCapture(reconciledEnvelope("MATCHED", "AUTO_MATCHED"));

        assertThat(saved.getStatus()).isEqualTo("MATCHED");
    }

    @Test
    void anUnmatchedOutcomeIsAlsoTerminal() {
        // Unmatched is a business outcome operators must see, not an absence of one.
        Transaction360Document saved = applyAndCapture(reconciledEnvelope("UNMATCHED", "MISSING_EXTERNAL"));

        assertThat(saved.getStatus()).isEqualTo("UNMATCHED");
    }

    @Test
    void reviewIsTerminalForTheProjectionEvenThoughAHumanStillHasWorkToDo() {
        Transaction360Document saved = applyAndCapture(reconciledEnvelope("REQUIRES_REVIEW", "AMOUNT_MISMATCH"));

        assertThat(saved.getStatus()).isEqualTo("REQUIRES_REVIEW");
    }

    @Test
    void reconciledAtIsStampedSoTheRateDenominatorCanBeCounted() {
        Transaction360Document saved = applyAndCapture(reconciledEnvelope("MATCHED", "AUTO_MATCHED"));

        assertThat(saved.isReconciled()).isTrue();
        assertThat(saved.getReconciledAt()).isEqualTo(OCCURRED);
    }

    @Test
    void reconciledAtIsDistinctFromUpdatedAt() {
        // updatedAt moves on every write; reconciledAt is what separates "finished" from
        // "seen recently".
        Transaction360Document saved = applyAndCapture(reconciledEnvelope("MATCHED", "AUTO_MATCHED"));

        assertThat(saved.getReconciledAt()).isEqualTo(OCCURRED);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void theTimelineRecordsTheReasoningNotJustTheVerdict() {
        Transaction360Document saved = applyAndCapture(reconciledEnvelope("REQUIRES_REVIEW", "AMOUNT_MISMATCH"));

        var step = saved.getTimeline().stream()
                .filter(n -> "RECONCILED".equals(n.stage()))
                .findFirst()
                .orElseThrow();

        assertThat(step.status()).isEqualTo("REQUIRES_REVIEW");
        // "one of forty" and "the only option" are the same verdict with very different confidence.
        assertThat(step.detail())
                .contains("AMOUNT_MISMATCH")
                .contains("rule-exact")
                .contains("4 candidates");
    }

    @Test
    void aStaleRedeliveryDoesNotRegressATerminalStatus() {
        // The outcome publishes at sequence 1. A redelivered TransactionReceived at sequence 0 must
        // not drag the projection back to RECEIVED.
        var reconciled = existingReceived();
        reconciled.setStatus("MATCHED");
        reconciled.setReconciledAt(OCCURRED);
        reconciled.recordSequence(1L);
        reconciled.setUpdatedAt(NOW);
        when(transactions.findById(TX)).thenReturn(Optional.of(reconciled));

        String staleReceived =
                """
                {"eventId":"%s","eventType":"TransactionReceived","eventVersion":2,\
                "aggregateType":"Transaction","aggregateId":"%s","sequenceNumber":0,\
                "occurredAt":"2026-01-15T12:00:00Z","correlationId":"%s","payload":%s}"""
                        .formatted(UUID.randomUUID(), TX, CORRELATION, quote("{\"reference\":\"R\"}"));

        assertThat(service.apply(staleReceived)).isFalse();
        verify(transactions, org.mockito.Mockito.never()).save(any());
    }
}
