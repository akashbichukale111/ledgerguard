package dev.ledgerguard.query.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import dev.ledgerguard.query.domain.audit.AuditRecord;

/**
 * One append-only audit entry.
 *
 * <p>No setters and no update path: the application role has no {@code UPDATE} or {@code DELETE}
 * grant on this table (migration {@code V1__audit_chain.sql}), so an entity that offered mutation
 * would be offering an operation the database refuses.
 */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    /** Assigned by the database sequence — this is what gives the chain a total order. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chain_index")
    private Long chainIndex;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "causation_id")
    private UUID causationId;

    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Column(name = "actor_source", nullable = false)
    private String actorSource;

    @Column(nullable = false)
    private String service;

    @Column(nullable = false)
    private String action;

    @Column(name = "aggregate_type")
    private String aggregateType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(nullable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state")
    private String afterState;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "record_hash", length = 64)
    private String recordHash;

    @Column(name = "hash_format", nullable = false)
    private short hashFormat;

    protected AuditEventEntity() {}

    public AuditEventEntity(
            UUID eventId,
            Instant occurredAt,
            Instant recordedAt,
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
            String previousHash,
            short hashFormat) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.actorSubject = actorSubject;
        this.actorRole = actorRole;
        this.actorSource = actorSource;
        this.service = service;
        this.action = action;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.outcome = outcome;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.previousHash = previousHash;
        this.hashFormat = hashFormat;
    }

    /**
     * Seals the record with its hash.
     *
     * <p>Called once, after the database has assigned {@code chainIndex} — the index is part of the
     * canonical form, so the hash cannot be computed before the insert flushes.
     */
    public void seal(String recordHash) {
        if (this.recordHash != null) {
            throw new IllegalStateException("audit record " + chainIndex + " is already sealed");
        }
        setRecordHash(recordHash);
    }

    void setRecordHash(String recordHash) {
        this.recordHash = recordHash;
    }

    /** The hashable projection of this row. */
    public AuditRecord toRecord() {
        return new AuditRecord(
                chainIndex,
                eventId,
                occurredAt,
                correlationId,
                causationId,
                actorSubject,
                actorRole,
                actorSource,
                service,
                action,
                aggregateType,
                aggregateId,
                outcome,
                beforeState,
                afterState,
                previousHash);
    }

    public Long chainIndex() {
        return chainIndex;
    }

    public String recordHash() {
        return recordHash;
    }

    public String previousHash() {
        return previousHash;
    }

    public String action() {
        return action;
    }

    public UUID correlationId() {
        return correlationId;
    }
}
