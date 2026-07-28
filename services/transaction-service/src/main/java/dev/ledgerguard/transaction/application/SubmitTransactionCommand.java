package dev.ledgerguard.transaction.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.transaction.domain.Direction;

/**
 * A validated instruction to record a transaction.
 *
 * <p>Constructed from an HTTP DTO only after Bean Validation has run. That is deliberately not
 * enough on its own: a DTO that passed {@code @NotNull} still has to satisfy the domain invariants
 * asserted in the compact constructor. Trusting a validated DTO as a domain invariant is a
 * recurring source of bugs, so both layers check.
 *
 * @param canonicalBody the canonicalised request body, hashed for idempotency comparison. Canonical
 *     means field-order-independent, so two semantically identical requests hash identically.
 */
public record SubmitTransactionCommand(
        String idempotencyKey,
        String reference,
        Money amount,
        Direction direction,
        String counterpartyId,
        String debitAccount,
        String creditAccount,
        LocalDate valueDate,
        LocalDate postingDate,
        String settlementSystem,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String actorSubject,
        String traceparent,
        String canonicalBody) {

    public SubmitTransactionCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(counterpartyId, "counterpartyId must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(canonicalBody, "canonicalBody must not be null");

        if (amount.isNegative()) {
            // Direction carries the sign; the amount never does. Encoding direction in the sign
            // makes every aggregation ambiguous.
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException("amount must not be zero");
        }
        if (Objects.equals(debitAccount, creditAccount)) {
            throw new IllegalArgumentException("debit and credit accounts must differ: both were " + debitAccount);
        }
    }

    /** Posting date defaults to the value date when the counterparty did not supply one. */
    public LocalDate effectivePostingDate() {
        return postingDate != null ? postingDate : valueDate;
    }
}
