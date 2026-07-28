package dev.ledgerguard.reconciliation.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import dev.ledgerguard.reconciliation.domain.matching.MatchClassification;
import dev.ledgerguard.reconciliation.domain.matching.ReconciliationOutcome;
import dev.ledgerguard.reconciliation.domain.matching.TransactionReconciledPayload;

@DisplayName("ReconciliationEventPublisher")
class ReconciliationEventPublisherTest {

    private static final String TOPIC = "reconciliation.events.v1";
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final UUID TX = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CORRELATION = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CAUSATION = UUID.fromString("33333333-3333-7333-8333-333333333333");

    private KafkaTemplate<String, String> kafka;
    private ObjectMapper objectMapper;
    private ReconciliationEventPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new ReconciliationEventPublisher(kafka, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), TOPIC);
    }

    private static TransactionReconciledPayload payload(ReconciliationOutcome outcome) {
        return new TransactionReconciledPayload(
                TX, outcome, MatchClassification.AUTO_MATCHED, List.of("ext-1"), "rule-exact", "v1", 4, NOW);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capture() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void keysByTransactionIdSoOutcomesStayOrderedAgainstTheEventTheyAnswer() {
        // Same partition key as TransactionReceived. A different key would let the outcome land on
        // another partition and be projected before the event it answers, which the projection's
        // sequence watermark would then throw away as stale.
        publisher.publish(payload(ReconciliationOutcome.MATCHED), CORRELATION, CAUSATION, null);

        assertThat(capture().key()).isEqualTo(TX.toString());
    }

    @Test
    void publishesToTheReconciliationTopic() {
        publisher.publish(payload(ReconciliationOutcome.MATCHED), CORRELATION, CAUSATION, null);

        assertThat(capture().topic()).isEqualTo(TOPIC);
    }

    @Test
    void theEnvelopeCarriesTheContractedTypeVersionAndSchemaRef() throws Exception {
        publisher.publish(payload(ReconciliationOutcome.MATCHED), CORRELATION, CAUSATION, null);

        JsonNode envelope = objectMapper.readTree(capture().value());

        assertThat(envelope.path("eventType").asText()).isEqualTo("TransactionReconciled");
        assertThat(envelope.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.path("schemaRef").asText()).isEqualTo("schemas/TransactionReconciled/v1.json");
        assertThat(envelope.path("aggregateId").asText()).isEqualTo(TX.toString());
    }

    @Test
    void sequenceNumberIsOneSoTheOutcomeFollowsTheReceivedEvent() throws Exception {
        // TransactionReceived publishes at 0. Anything lower here is discarded by the watermark.
        publisher.publish(payload(ReconciliationOutcome.MATCHED), CORRELATION, CAUSATION, null);

        assertThat(objectMapper
                        .readTree(capture().value())
                        .path("sequenceNumber")
                        .asLong())
                .isEqualTo(1L);
    }

    @Test
    void causationIdLinksBackToTheEventThatStartedTheFlow() throws Exception {
        publisher.publish(payload(ReconciliationOutcome.MATCHED), CORRELATION, CAUSATION, null);

        JsonNode envelope = objectMapper.readTree(capture().value());

        assertThat(envelope.path("correlationId").asText()).isEqualTo(CORRELATION.toString());
        assertThat(envelope.path("causationId").asText()).isEqualTo(CAUSATION.toString());
    }

    @Test
    void thePayloadCarriesTheOutcomeAndTheReasoning() throws Exception {
        publisher.publish(payload(ReconciliationOutcome.REQUIRES_REVIEW), CORRELATION, CAUSATION, null);

        JsonNode envelope = objectMapper.readTree(capture().value());
        JsonNode body = objectMapper.readTree(envelope.path("payload").asText());

        assertThat(body.path("outcome").asText()).isEqualTo("REQUIRES_REVIEW");
        assertThat(body.path("classification").asText()).isEqualTo("AUTO_MATCHED");
        // The explanation is part of the contract: an outcome without reasoning is not actionable.
        assertThat(body.path("ruleId").asText()).isEqualTo("rule-exact");
        assertThat(body.path("ruleSetVersion").asText()).isEqualTo("v1");
        assertThat(body.path("candidatePoolSize").asInt()).isEqualTo(4);
    }

    @Test
    void traceContextTravelsAsAKafkaHeaderBecauseItDoesNotCrossABrokerOnItsOwn() {
        publisher.publish(
                payload(ReconciliationOutcome.MATCHED),
                CORRELATION,
                CAUSATION,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        var header = capture().headers().lastHeader("traceparent");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    @Test
    void aMissingTraceparentIsOmittedRatherThanSentAsTheStringNull() {
        publisher.publish(payload(ReconciliationOutcome.MATCHED), CORRELATION, CAUSATION, null);

        assertThat(capture().headers().lastHeader("traceparent")).isNull();
    }
}
