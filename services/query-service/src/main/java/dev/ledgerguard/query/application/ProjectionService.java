package dev.ledgerguard.query.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.ledgerguard.query.adapter.out.mongo.ProcessedEventDocument;
import dev.ledgerguard.query.adapter.out.mongo.ProcessedEventProjectionRepository;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;

/**
 * Applies events to the read model.
 *
 * <h2>Idempotency</h2>
 *
 * Delivery is at-least-once, so this method is called more than once for the same event as a matter
 * of routine — on consumer restart, on rebalance, and on any DLQ replay. Two independent guards:
 *
 * <ol>
 *   <li>a {@code processed_event_projection} document keyed on {@code (consumerGroup, eventId)},
 *       whose {@code _id} makes the uniqueness a primary-key property rather than an application
 *       check;
 *   <li>a per-document {@code lastAppliedSequence} watermark, so a <b>stale</b> event that arrives
 *       after newer ones — which the non-blocking retry ladder makes routine (ADR-0012) — cannot
 *       regress the projection.
 * </ol>
 *
 * <p>The second guard is what makes a full topic replay safe: replaying from offset 0 re-applies
 * every event in order and converges on the same document.
 *
 * <h2>Honest limitation</h2>
 *
 * The dedupe document and the projection update are two writes to MongoDB and are <b>not</b> in one
 * transaction here. A crash between them re-applies the event on redelivery — which is harmless
 * precisely because the projection itself is idempotent. Making them atomic would require a MongoDB
 * multi-document transaction (a replica set), which the local single-node stack does not provide.
 * The projection's own idempotency is what carries the guarantee, and
 * {@code ProjectionIT.duplicateDeliveryLeavesProjectionIdentical} proves it.
 */
@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);
    private static final String CONSUMER_GROUP = "query-service";

    private final Transaction360Repository transactions;
    private final ProcessedEventProjectionRepository processedEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter applied;
    private final Counter duplicatesSkipped;
    private final Counter staleSkipped;

    public ProjectionService(
            Transaction360Repository transactions,
            ProcessedEventProjectionRepository processedEvents,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meters) {
        this.transactions = transactions;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.applied = Counter.builder("ledgerguard.projection.applied")
                .description("Events applied to the read model")
                .register(meters);
        this.duplicatesSkipped = Counter.builder("ledgerguard.projection.duplicate.skipped")
                .description("Redelivered events skipped by the dedupe guard")
                .register(meters);
        this.staleSkipped = Counter.builder("ledgerguard.projection.stale.skipped")
                .description("Out-of-order events skipped by the sequence watermark")
                .register(meters);
    }

    /**
     * Applies one enveloped event.
     *
     * @return true if the event changed the projection, false if it was a duplicate or stale
     */
    public boolean apply(String envelopeJson) {
        try {
            JsonNode envelope = objectMapper.readTree(envelopeJson);
            String eventId = envelope.path("eventId").asText();

            String dedupeKey = ProcessedEventDocument.key(CONSUMER_GROUP, eventId);
            if (processedEvents.existsById(dedupeKey)) {
                duplicatesSkipped.increment();
                log.debug("skipping duplicate event {}", eventId);
                return false;
            }

            boolean changed = project(envelope);

            processedEvents.save(new ProcessedEventDocument(CONSUMER_GROUP, eventId, clock.instant()));
            if (changed) {
                applied.increment();
            }
            return changed;

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Deserialization failure is NON-RETRYABLE: the payload will fail identically forever.
            // Classification and DLT routing are Phase 7; here it is surfaced rather than swallowed.
            throw new NonRetryableProjectionException("malformed envelope", e);
        }
    }

    private boolean project(JsonNode envelope) throws com.fasterxml.jackson.core.JsonProcessingException {
        String aggregateId = envelope.path("aggregateId").asText();
        String eventType = envelope.path("eventType").asText();
        long sequence = envelope.path("sequenceNumber").asLong();
        Instant occurredAt = Instant.parse(envelope.path("occurredAt").asText());

        Transaction360Document document =
                transactions.findById(aggregateId).orElseGet(() -> new Transaction360Document(aggregateId));

        if (!document.isNewerThan(sequence) && document.getUpdatedAt() != null) {
            // A stale redelivery. Skipping is what stops the retry ladder regressing the read model.
            staleSkipped.increment();
            log.debug("skipping stale event for {} at sequence {}", aggregateId, sequence);
            return false;
        }

        JsonNode payload = objectMapper.readTree(envelope.path("payload").asText());

        if ("TransactionReceived".equals(eventType)) {
            document.setReference(payload.path("reference").asText());
            // String, not a number: BSON's default numeric types cannot hold an exact decimal.
            document.setAmount(payload.path("amount").asText());
            document.setCurrency(payload.path("currency").asText());
            document.setDirection(payload.path("direction").asText());
            document.setCounterpartyId(payload.path("counterpartyId").asText());
            document.setStatus("RECEIVED");
            document.setOccurredAt(occurredAt);
            document.setCorrelationId(
                    UUID.fromString(envelope.path("correlationId").asText()));
            document.addNode(new Transaction360Document.LifecycleNode(
                    "INGESTED", "transaction-service", occurredAt, clock.instant(), "SUCCESS", "transaction accepted"));

        } else if ("TransactionReconciled".equals(eventType)) {
            // The terminal outcome. Until this event existed nothing ever moved a transaction off
            // RECEIVED, so the read model had no finished population and match rate and error rate
            // could not be computed from it at all.
            String outcome = payload.path("outcome").asText();
            document.setStatus(outcome);
            document.setReconciledAt(occurredAt);

            document.addNode(new Transaction360Document.LifecycleNode(
                    "RECONCILED",
                    "reconciliation-service",
                    occurredAt,
                    clock.instant(),
                    outcome,
                    describeOutcome(payload)));

        } else {
            document.addNode(new Transaction360Document.LifecycleNode(
                    eventType, "reconciliation-service", occurredAt, clock.instant(), "SUCCESS", eventType));
        }

        document.recordSequence(sequence);
        document.setUpdatedAt(clock.instant());
        transactions.save(document);
        return true;
    }

    /**
     * Renders the engine's reasoning into the one line the console's timeline shows.
     *
     * <p>The rule and the candidate pool size are the two facts an analyst asks for first: "one of
     * forty" and "the only option" are the same outcome with very different confidence behind it.
     */
    private static String describeOutcome(JsonNode payload) {
        return "%s via %s (rule %s, %d candidates)"
                .formatted(
                        payload.path("classification").asText(),
                        payload.path("ruleSetVersion").asText(),
                        payload.path("ruleId").asText(),
                        payload.path("candidatePoolSize").asInt());
    }

    /**
     * Projection lag: event time to projection time.
     *
     * <p>Surfaced in the console header so eventual consistency is <b>visible rather than hidden</b>.
     * A CQRS system that pretends its read model is current lies to its operators at exactly the
     * moment they most need the truth.
     */
    public Duration lagFor(String transactionId) {
        return transactions
                .findById(transactionId)
                .filter(d -> d.getOccurredAt() != null && d.getUpdatedAt() != null)
                .map(d -> Duration.between(d.getOccurredAt(), d.getUpdatedAt()))
                .orElse(Duration.ZERO);
    }

    /** A failure that must never be retried, because it will fail identically every time. */
    public static class NonRetryableProjectionException extends RuntimeException {
        public NonRetryableProjectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
