package dev.ledgerguard.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The wrapper carried by every Kafka message in LedgerGuard.
 *
 * <p>Validated against {@code schemas/envelope/v1.json}. Each field exists because a specific
 * mechanism breaks without it — this is not metadata for its own sake:
 *
 * <ul>
 *   <li>{@code eventId} — the consumer dedupe key. {@code (consumerGroup, eventId)} is unique, and
 *       that is what makes at-least-once delivery survivable (ADR-0006).
 *   <li>{@code aggregateId} — the Kafka partition key. Ordering exists per partition and therefore
 *       per aggregate. <b>There is no global ordering.</b>
 *   <li>{@code correlationId} — one business flow, end to end.
 *   <li>{@code causationId} — the event or command that <i>directly</i> caused this one. With
 *       correlationId this reconstructs a causal tree, which is what Transaction 360 renders.
 *   <li>{@code traceparent} — W3C trace context. Written to and read from Kafka headers explicitly;
 *       it does not propagate across a broker on its own.
 * </ul>
 *
 * <p>{@code payload} is deliberately a {@code String} of JSON rather than a parsed type: the
 * envelope must be readable, routable, and dead-letterable even when the payload cannot be parsed
 * or its schema version is unknown. Parsing eagerly would make a malformed payload undeliverable to
 * the DLQ, which is precisely where it needs to go.
 *
 * @param sequenceNumber position within the aggregate's stream, starting at 0
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        long sequenceNumber,
        Instant occurredAt,
        Instant recordedAt,
        UUID correlationId,
        UUID causationId,
        Actor actor,
        String traceparent,
        String schemaRef,
        String payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(schemaRef, "schemaRef must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1, got " + eventVersion);
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 0, got " + sequenceNumber);
        }
        // causationId and traceparent are intentionally nullable: a flow root has no cause, and an
        // internally-emitted event may have no active span.
    }

    /** Who or what caused this event. Recorded on every audit entry. */
    public record Actor(String subject, Role role, Source source) {
        public Actor {
            Objects.requireNonNull(subject, "actor subject must not be null");
            Objects.requireNonNull(role, "actor role must not be null");
            Objects.requireNonNull(source, "actor source must not be null");
        }

        /** {@code SYSTEM} is not a grantable role — it marks actions with no human originator. */
        public enum Role {
            AUDITOR,
            ANALYST,
            OPERATIONS,
            ADMIN,
            SYSTEM
        }

        /** {@code REPLAY} marks events republished from the DLQ, so replays stay distinguishable. */
        public enum Source {
            API,
            KAFKA,
            SCHEDULER,
            REPLAY
        }
    }
}
