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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.transaction.domain.Direction;

/** A single double-entry posting belonging to one transaction. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntryEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private Direction entryType;

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

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(nullable = false)
    private boolean matched;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected LedgerEntryEntity() {
        // for JPA
    }

    public LedgerEntryEntity(
            UUID id,
            UUID transactionId,
            String account,
            Direction entryType,
            Money amount,
            LocalDate valueDate,
            LocalDate postingDate,
            Instant now) {
        this.id = id;
        this.transactionId = transactionId;
        this.account = account;
        this.entryType = entryType;
        this.amount = amount.amount();
        this.currency = amount.currency().code();
        this.valueDate = valueDate;
        this.postingDate = postingDate;
        this.matched = false;
        this.recordedAt = now;
    }

    public Money money() {
        return Money.of(amount, CurrencyCode.of(currency));
    }

    public UUID id() {
        return id;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public String account() {
        return account;
    }

    public Direction entryType() {
        return entryType;
    }

    public boolean matched() {
        return matched;
    }
}
