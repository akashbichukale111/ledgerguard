package dev.ledgerguard.reconciliation.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import dev.ledgerguard.contracts.EventEnvelope;
import dev.ledgerguard.reconciliation.domain.matching.TransactionReconciledPayload;

/**
 * Publishes {@code TransactionReconciled} to {@code reconciliation.events.v1}.
 *
 * <p>Constructed by {@code ReconciliationConfig} rather than component-scanned: the topic name comes
 * from configuration and cannot be autowired as a bare String.
 *
 * <p><b>Ordering.</b> Keyed by {@code transactionId}, the same key
 * {@code TransactionReceived} uses. That is what stops an outcome landing on a different partition
 * from the event it answers and being projected before it — the projection's sequence watermark
 * would then discard the outcome as stale.
 *
 * <p><b>Delivery.</b> This publishes directly rather than through an outbox, and that is a real
 * difference from the write path. transaction-service writes its event in the same transaction as
 * the aggregate, so a crash cannot lose it. Here the matching outcome is already durable in the
 * saga tables before this is called, so a failed publish is recoverable by replaying from the saga
 * — but it is recoverable by an operator, not automatically. Making this an outbox too is the
 * honest end state; see the phase-19 report.
 */
public class ReconciliationEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationEventPublisher.class);

    private static final String EVENT_TYPE = "TransactionReconciled";
    private static final int EVENT_VERSION = 1;
    private static final String AGGREGATE_TYPE = "Transaction";
    private static final String SCHEMA_REF = "schemas/TransactionReconciled/v1.json";

    /** Bounded so a broker that accepts the connection but never answers cannot wedge the saga. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String topic;

    public ReconciliationEventPublisher(
            KafkaTemplate<String, String> kafka, ObjectMapper objectMapper, Clock clock, String topic) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    /**
     * @param causationId the {@code eventId} of the {@code TransactionReceived} that started this
     *     flow, so the two sides join up in the audit trail
     */
    public void publish(
            TransactionReconciledPayload payload, UUID correlationId, UUID causationId, String traceparent) {

        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(correlationId, "correlationId");

        try {
            EventEnvelope envelope = new EventEnvelope(
                    UUID.randomUUID(),
                    EVENT_TYPE,
                    EVENT_VERSION,
                    AGGREGATE_TYPE,
                    payload.transactionId(),
                    // The outcome is the second event in this aggregate's stream. TransactionReceived
                    // publishes at 0; anything lower here would be discarded by the projection's
                    // watermark as a stale redelivery.
                    1L,
                    payload.reconciledAt(),
                    clock.instant(),
                    correlationId,
                    causationId,
                    new EventEnvelope.Actor(
                            "reconciliation-engine",
                            EventEnvelope.Actor.Role.SYSTEM,
                            EventEnvelope.Actor.Source.SCHEDULER),
                    traceparent,
                    SCHEMA_REF,
                    objectMapper.writeValueAsString(payload));

            ProducerRecord<String, String> message = new ProducerRecord<>(
                    topic, payload.transactionId().toString(), objectMapper.writeValueAsString(envelope));

            // Trace context does not cross a broker on its own; these headers are what carry it.
            addHeader(message, "correlationId", correlationId.toString());
            addHeader(message, "eventType", EVENT_TYPE);
            addHeader(message, "eventId", envelope.eventId().toString());
            if (traceparent != null && !traceparent.isBlank()) {
                addHeader(message, "traceparent", traceparent);
            }

            kafka.send(message).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info(
                    "published {} for transaction {} outcome={}",
                    EVENT_TYPE,
                    payload.transactionId(),
                    payload.outcome());

        } catch (JsonProcessingException e) {
            // The payload is a closed shape of strings, an int, an enum and an Instant. A failure
            // here means the type changed in a way that is not serialisable — a bug, not a runtime
            // condition.
            throw new IllegalStateException("could not serialise " + EVENT_TYPE + " envelope", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReconciliationPublishException("interrupted publishing " + EVENT_TYPE, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ReconciliationPublishException("broker did not acknowledge " + EVENT_TYPE, e);
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
        record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
    }

    /** A publish that did not reach the broker. Retryable — the outcome itself is already durable. */
    public static class ReconciliationPublishException extends RuntimeException {
        public ReconciliationPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
