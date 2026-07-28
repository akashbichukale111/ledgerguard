package dev.ledgerguard.transaction.adapter.out.persistence;

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

/**
 * A pending event publication.
 *
 * <p>Inserted in the <b>same local transaction</b> as the aggregate change. That single fact is
 * what eliminates the dual-write problem: either both the state change and the intent to publish
 * are durable, or neither is (ADR-0005).
 */
@Entity
@Table(name = "outbox_record")
public class OutboxRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    /** The Kafka partition key. This is what gives per-aggregate ordering. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String headers;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until the broker has acknowledged. The poller's claim query filters on this. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    protected OutboxRecordEntity() {
        // for JPA
    }

    public OutboxRecordEntity(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int eventVersion,
            String payload,
            String headers,
            Instant occurredAt,
            Instant now) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.headers = headers;
        this.occurredAt = occurredAt;
        this.createdAt = now;
        this.publishAttempts = 0;
    }

    public void markPublished(Instant now) {
        this.publishedAt = now;
    }

    public void recordAttempt() {
        this.publishAttempts++;
    }

    public Long id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public String payload() {
        return payload;
    }

    public String headers() {
        return headers;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public int publishAttempts() {
        return publishAttempts;
    }
}
