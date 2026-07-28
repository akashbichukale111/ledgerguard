package dev.ledgerguard.query.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.ledgerguard.query.adapter.out.mongo.ProcessedEventProjectionRepository;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;
import dev.ledgerguard.query.application.ProjectionService;

/**
 * The Phase 6 projection gate: a topic replay rebuilds the read model correctly, and duplicate
 * delivery leaves it identical.
 */
@SpringBootTest
@Testcontainers
class ProjectionIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledgerguard_audit")
            .withInitScript("db/testcontainers-init.sql");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        // The listener is driven directly: feeding ProjectionService is what is under test, and
        // waiting on a real broker would add flakiness without adding coverage. The consumer's own
        // delivery mechanics are covered where they belong, in Phase 7.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    ProjectionService projections;

    @Autowired
    Transaction360Repository transactions;

    @Autowired
    ProcessedEventProjectionRepository processedEvents;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        transactions.deleteAll();
        processedEvents.deleteAll();
    }

    /** Builds a realistic enveloped event, matching what the outbox publisher actually emits. */
    private String envelope(String transactionId, String eventId, long sequence, String eventType, String amount) {
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "transactionId", transactionId,
                    "reference", "INV-2026-0001",
                    "amount", amount,
                    "currency", "USD",
                    "direction", "DEBIT",
                    "counterpartyId", "CP-ACME-001"));
            return objectMapper.writeValueAsString(java.util.Map.ofEntries(
                    java.util.Map.entry("eventId", eventId),
                    java.util.Map.entry("eventType", eventType),
                    java.util.Map.entry("eventVersion", 2),
                    java.util.Map.entry("aggregateType", "Transaction"),
                    java.util.Map.entry("aggregateId", transactionId),
                    java.util.Map.entry("sequenceNumber", sequence),
                    java.util.Map.entry("occurredAt", "2026-07-28T00:00:00Z"),
                    java.util.Map.entry("recordedAt", "2026-07-28T00:00:01Z"),
                    java.util.Map.entry("correlationId", UUID.randomUUID().toString()),
                    java.util.Map.entry("schemaRef", "schemas/TransactionReceived/v2.json"),
                    java.util.Map.entry("payload", payload)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("duplicate delivery")
    class DuplicateDelivery {

        @Test
        @DisplayName("the same event delivered three times leaves the projection identical")
        void duplicateDeliveryLeavesProjectionIdentical() {
            String transactionId = UUID.randomUUID().toString();
            String eventId = UUID.randomUUID().toString();
            String event = envelope(transactionId, eventId, 0, "TransactionReceived", "1250.75");

            assertThat(projections.apply(event)).as("first delivery applies").isTrue();
            Transaction360Document afterFirst =
                    transactions.findById(transactionId).orElseThrow();

            assertThat(projections.apply(event))
                    .as("second delivery is skipped")
                    .isFalse();
            assertThat(projections.apply(event)).as("third delivery is skipped").isFalse();

            Transaction360Document afterThird =
                    transactions.findById(transactionId).orElseThrow();

            assertThat(transactions.count()).isEqualTo(1);
            assertThat(afterThird.getTimeline()).hasSameSizeAs(afterFirst.getTimeline());
            assertThat(afterThird.getAmount()).isEqualTo(afterFirst.getAmount());
            assertThat(afterThird.getLastAppliedSequence()).isEqualTo(afterFirst.getLastAppliedSequence());
            assertThat(processedEvents.count())
                    .as("one dedupe record, not three")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a distinct event for the same aggregate is NOT skipped")
        void distinctEventsStillApply() {
            String transactionId = UUID.randomUUID().toString();
            projections.apply(
                    envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "100.00"));
            projections.apply(
                    envelope(transactionId, UUID.randomUUID().toString(), 1, "ReconciliationCompleted", "100.00"));

            Transaction360Document document =
                    transactions.findById(transactionId).orElseThrow();
            assertThat(document.getTimeline()).hasSize(2);
            assertThat(document.getLastAppliedSequence()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("replay rebuilds the read model")
    class Replay {

        @Test
        @DisplayName("dropping the projection and replaying the topic reproduces it exactly")
        void replayRebuildsProjection() {
            // Build an event log for three transactions, as a topic would hold it.
            List<String> topic = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                String transactionId = UUID.randomUUID().toString();
                ids.add(transactionId);
                topic.add(envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "500.00"));
                topic.add(
                        envelope(transactionId, UUID.randomUUID().toString(), 1, "ReconciliationCompleted", "500.00"));
            }

            topic.forEach(projections::apply);
            List<Transaction360Document> original = snapshot(ids);
            assertThat(original).hasSize(3);

            // Simulate the operational procedure from docs/operations.md: drop the read model and
            // replay the topic from offset 0. The read model is disposable BY DESIGN — that is the
            // point of CQRS with an event log, and it is what makes projection bugs recoverable
            // rather than data-loss events.
            transactions.deleteAll();
            processedEvents.deleteAll();
            assertThat(transactions.count()).isZero();

            topic.forEach(projections::apply);
            List<Transaction360Document> rebuilt = snapshot(ids);

            assertThat(rebuilt).hasSameSizeAs(original);
            for (int i = 0; i < original.size(); i++) {
                Transaction360Document before = original.get(i);
                Transaction360Document after = rebuilt.get(i);
                assertThat(after.getTransactionId()).isEqualTo(before.getTransactionId());
                assertThat(after.getAmount()).isEqualTo(before.getAmount());
                assertThat(after.getCurrency()).isEqualTo(before.getCurrency());
                assertThat(after.getReference()).isEqualTo(before.getReference());
                assertThat(after.getStatus()).isEqualTo(before.getStatus());
                assertThat(after.getLastAppliedSequence()).isEqualTo(before.getLastAppliedSequence());
                assertThat(after.getTimeline()).hasSameSizeAs(before.getTimeline());
                assertThat(after.getTimeline().stream().map(Transaction360Document.LifecycleNode::stage))
                        .containsExactlyElementsOf(before.getTimeline().stream()
                                .map(Transaction360Document.LifecycleNode::stage)
                                .toList());
            }
        }

        @Test
        @DisplayName("a partial replay over an existing projection is also safe")
        void partialReplayIsIdempotent() {
            String transactionId = UUID.randomUUID().toString();
            String first = envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "42.00");
            String second =
                    envelope(transactionId, UUID.randomUUID().toString(), 1, "ReconciliationCompleted", "42.00");

            projections.apply(first);
            projections.apply(second);
            Transaction360Document before = transactions.findById(transactionId).orElseThrow();

            // Replay WITHOUT clearing: exactly what a DLQ replay does.
            projections.apply(first);
            projections.apply(second);
            Transaction360Document after = transactions.findById(transactionId).orElseThrow();

            assertThat(after.getTimeline()).hasSameSizeAs(before.getTimeline());
            assertThat(transactions.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("out-of-order delivery")
    class Ordering {

        @Test
        @DisplayName("a stale event arriving late does not regress the projection")
        void staleEventDoesNotRegress() {
            // The retry ladder makes this routine: an event sent to retry.1 is reprocessed after
            // later events for the same key have already been applied (ADR-0012).
            String transactionId = UUID.randomUUID().toString();

            projections.apply(
                    envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "100.00"));
            projections.apply(
                    envelope(transactionId, UUID.randomUUID().toString(), 5, "ReconciliationCompleted", "100.00"));

            Transaction360Document beforeStale =
                    transactions.findById(transactionId).orElseThrow();
            assertThat(beforeStale.getLastAppliedSequence()).isEqualTo(5);

            // A distinct event id, so the dedupe guard does NOT catch it — the sequence watermark
            // is the guard being tested here.
            boolean changed =
                    projections.apply(envelope(transactionId, UUID.randomUUID().toString(), 2, "StaleEvent", "100.00"));

            Transaction360Document afterStale =
                    transactions.findById(transactionId).orElseThrow();
            assertThat(changed).isFalse();
            assertThat(afterStale.getLastAppliedSequence()).isEqualTo(5);
            assertThat(afterStale.getTimeline()).hasSameSizeAs(beforeStale.getTimeline());
        }

        @Test
        @DisplayName("shuffled delivery of one aggregate's events converges on the same document")
        void shuffledDeliveryConverges() {
            String transactionId = UUID.randomUUID().toString();
            List<String> events = new ArrayList<>();
            events.add(
                    envelope(transactionId, "11111111-1111-7111-8111-111111111111", 0, "TransactionReceived", "77.00"));
            events.add(envelope(transactionId, "22222222-2222-7222-8222-222222222222", 1, "CaseOpened", "77.00"));
            events.add(envelope(
                    transactionId, "33333333-3333-7333-8333-333333333333", 2, "ReconciliationCompleted", "77.00"));

            events.forEach(projections::apply);
            long inOrderSequence =
                    transactions.findById(transactionId).orElseThrow().getLastAppliedSequence();

            transactions.deleteAll();
            processedEvents.deleteAll();

            List<String> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, new Random(42));
            shuffled.forEach(projections::apply);

            // The watermark means the highest sequence wins regardless of arrival order.
            assertThat(transactions.findById(transactionId).orElseThrow().getLastAppliedSequence())
                    .isEqualTo(inOrderSequence);
        }
    }

    @Nested
    @DisplayName("projection lag is measurable")
    class Lag {

        @Test
        void lagIsTheDifferenceBetweenEventTimeAndProjectionTime() {
            String transactionId = UUID.randomUUID().toString();
            projections.apply(envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "9.99"));

            // occurredAt is 2026-07-28T00:00:00Z and updatedAt is now, so the lag is large and
            // positive. What matters is that it is MEASURED and non-negative — the console surfaces
            // it so eventual consistency is visible rather than hidden.
            assertThat(projections.lagFor(transactionId)).isPositive();
        }

        @Test
        void lagForAnUnknownTransactionIsZeroRatherThanAnError() {
            assertThat(projections.lagFor(UUID.randomUUID().toString())).isZero();
        }
    }

    @Nested
    @DisplayName("money survives the projection")
    class MoneyFidelity {

        @Test
        void amountsAreStoredAsStringsNotNumbers() {
            String transactionId = UUID.randomUUID().toString();
            // A value that a double cannot represent exactly.
            projections.apply(
                    envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "1234567.89"));

            Transaction360Document document =
                    transactions.findById(transactionId).orElseThrow();
            assertThat(document.getAmount())
                    .as("stored verbatim as a string; a BSON double would round it")
                    .isEqualTo("1234567.89");
        }

        @Test
        void trailingZeroesArePreservedExactly() {
            String transactionId = UUID.randomUUID().toString();
            projections.apply(envelope(transactionId, UUID.randomUUID().toString(), 0, "TransactionReceived", "10.00"));

            assertThat(transactions.findById(transactionId).orElseThrow().getAmount())
                    .as("10.00 must not become 10")
                    .isEqualTo("10.00");
        }
    }

    private List<Transaction360Document> snapshot(List<String> ids) {
        List<Transaction360Document> documents = new ArrayList<>();
        for (String id : ids) {
            transactions.findById(id).ifPresent(documents::add);
        }
        return documents;
    }
}
