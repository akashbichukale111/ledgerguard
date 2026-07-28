package dev.ledgerguard.transaction.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.transaction.domain.Direction;
import dev.ledgerguard.transaction.domain.TransactionStatus;

/** JPA mapping for the Transaction aggregate root. */
@Entity
@Table(name = "transaction")
public class TransactionEntity {

    @Id
    private UUID id;

    /**
     * Optimistic concurrency. Two concurrent writers produce an
     * {@code OptimisticLockingFailureException} for the loser rather than a lost update — a lost
     * update here is a lost financial instruction.
     */
    @Version
    private long version;

    @Column(nullable = false)
    private String reference;

    @Column(name = "counterparty_id", nullable = false)
    private String counterpartyId;

    @Convert(converter = MoneyAmountConverter.class)
    @Column(nullable = false)
    private BigDecimal amount;

    /**
     * ISO 4217 code. Mapped as CHAR because the migration declares {@code CHAR(3)} — a currency
     * code is always exactly three characters. Hibernate defaults a String to VARCHAR, which
     * {@code ddl-auto: validate} rightly rejects.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "posting_date")
    private LocalDate postingDate;

    @Column(name = "settlement_system")
    private String settlementSystem;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    protected TransactionEntity() {
        // for JPA
    }

    public TransactionEntity(
            UUID id,
            String reference,
            String counterpartyId,
            Money amount,
            Direction direction,
            LocalDate valueDate,
            LocalDate postingDate,
            String settlementSystem,
            Instant occurredAt,
            Instant now,
            UUID correlationId) {
        this.id = id;
        this.reference = reference;
        this.counterpartyId = counterpartyId;
        this.amount = amount.amount();
        this.currency = amount.currency().code();
        this.direction = direction;
        this.status = TransactionStatus.RECEIVED;
        this.valueDate = valueDate;
        this.postingDate = postingDate;
        this.settlementSystem = settlementSystem;
        this.occurredAt = occurredAt;
        this.recordedAt = now;
        this.updatedAt = now;
        this.correlationId = correlationId;
    }

    /**
     * Applies a state transition, rejecting any move the allow-table forbids.
     *
     * @throws dev.ledgerguard.common.core.error.IllegalStateTransitionException on an illegal move
     */
    public void transitionTo(TransactionStatus target, Instant now) {
        this.status = this.status.transitionTo(target);
        this.updatedAt = now;
    }

    /** Reassembles the domain value from its two columns. */
    public Money money() {
        return Money.of(amount, CurrencyCode.of(currency));
    }

    public UUID id() {
        return id;
    }

    public long version() {
        return version;
    }

    public String reference() {
        return reference;
    }

    public String counterpartyId() {
        return counterpartyId;
    }

    public Direction direction() {
        return direction;
    }

    public TransactionStatus status() {
        return status;
    }

    public LocalDate valueDate() {
        return valueDate;
    }

    public LocalDate postingDate() {
        return postingDate;
    }

    public String settlementSystem() {
        return settlementSystem;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public UUID correlationId() {
        return correlationId;
    }
}
