package dev.ledgerguard.query.domain.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The canonical, hashable content of one audit entry.
 *
 * <p>Deliberately separate from the JPA entity: hashing must depend only on the fields that carry
 * meaning, in a fixed order, with no persistence concerns leaking in. If the entity gained a column
 * tomorrow, the chain must not silently change shape.
 *
 * @param chainIndex position in the chain, assigned by the database sequence
 * @param previousHash the {@code recordHash} of the record at {@code chainIndex - 1}; null only for
 *     the genesis record
 */
public record AuditRecord(
        long chainIndex,
        UUID eventId,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String actorSubject,
        String actorRole,
        String actorSource,
        String service,
        String action,
        String aggregateType,
        UUID aggregateId,
        String outcome,
        String beforeState,
        String afterState,
        String previousHash) {

    public AuditRecord {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(actorSubject, "actorSubject must not be null");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        Objects.requireNonNull(actorSource, "actorSource must not be null");
        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
