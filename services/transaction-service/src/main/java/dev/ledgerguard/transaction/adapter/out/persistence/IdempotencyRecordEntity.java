package dev.ledgerguard.transaction.adapter.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * API-level idempotency (ADR-0006, layer 1).
 *
 * <p>Correctness rests on the primary key, not on application logic: two concurrent requests with
 * the same key race, one inserts, and the other takes a constraint violation and returns the stored
 * response. A check-then-act in application code would have a window between the two.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    public enum State {
        IN_FLIGHT,
        COMPLETED,
        FAILED
    }

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(nullable = false)
    private String endpoint;

    /**
     * SHA-256 of the canonicalised body. Same key + different body is a 409, never a replay.
     *
     * <p>Mapped as CHAR, not VARCHAR: the migration declares {@code CHAR(64)} because a hex SHA-256
     * is always exactly 64 characters. Hibernate defaults a String to VARCHAR, and
     * {@code ddl-auto: validate} correctly rejects the mismatch — the mapping is corrected here
     * rather than the migration edited, because a committed migration is immutable.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_body_hash", nullable = false, length = 64)
    private String requestBodyHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    /**
     * The exact bytes of the original response.
     *
     * <p>Plain text, not JSON: PostgreSQL's {@code jsonb} normalises whitespace and key order on
     * write, so a replay would return a semantically-equal but not byte-equal body. See migration
     * V3.
     */
    @Column(name = "response_body")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecordEntity() {
        // for JPA
    }

    public IdempotencyRecordEntity(String idempotencyKey, String endpoint, String requestBodyHash, Instant now) {
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestBodyHash = requestBodyHash;
        this.state = State.IN_FLIGHT;
        this.createdAt = now;
    }

    public void complete(int status, String body, Instant now) {
        this.responseStatus = status;
        this.responseBody = body;
        this.state = State.COMPLETED;
        this.completedAt = now;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String endpoint() {
        return endpoint;
    }

    public String requestBodyHash() {
        return requestBodyHash;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public State state() {
        return state;
    }
}
