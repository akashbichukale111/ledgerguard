package dev.ledgerguard.reconciliation.domain.matching;

import java.time.LocalDate;
import java.util.Objects;

import dev.ledgerguard.common.core.money.Money;

/**
 * One entry presented to the matching engine.
 *
 * <p>Deliberately framework-free and immutable: the engine is pure domain code, so its tests run in
 * milliseconds with no Spring context and PIT can mutate it cheaply.
 *
 * @param id stable identifier — also the final deterministic tie-breaker (§4.2 stage 5)
 * @param rawReference the reference exactly as supplied; normalisation never mutates it in place,
 *     because an explanation has to be able to show what the counterparty actually sent
 */
public record MatchableEntry(
        String id, String rawReference, Money amount, LocalDate valueDate, String counterpartyId, Side side) {

    public MatchableEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(rawReference, "rawReference must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(counterpartyId, "counterpartyId must not be null");
        Objects.requireNonNull(side, "side must not be null");
    }
}
