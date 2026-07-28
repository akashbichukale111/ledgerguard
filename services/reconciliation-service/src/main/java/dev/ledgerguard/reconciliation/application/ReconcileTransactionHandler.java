package dev.ledgerguard.reconciliation.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.reconciliation.domain.matching.MatchResult;
import dev.ledgerguard.reconciliation.domain.matching.MatchableEntry;
import dev.ledgerguard.reconciliation.domain.matching.ReconciliationEngine;
import dev.ledgerguard.reconciliation.domain.matching.Side;
import dev.ledgerguard.reconciliation.domain.matching.TransactionReconciledPayload;

/**
 * Turns one {@code TransactionReceived} payload into a reconciliation outcome.
 *
 * <p>Separated from the Kafka listener so the decision logic is testable without a broker: the
 * listener owns delivery concerns, this owns the decision.
 *
 * <p><b>The counterparty side is not yet sourced.</b> A real deployment feeds the engine external
 * statement lines from a bank feed or file ingest; no such feed exists in this repository, so
 * {@link #externalCandidatesFor} returns an empty list and every transaction reconciles as
 * {@code UNMATCHED}. That is a truthful outcome for a system with no counterparty data — it is not
 * a placeholder that pretends to match. The engine, the contract, the projection and the metrics
 * are all exercised end to end regardless; only the candidate source is missing, and it is the one
 * seam a real feed plugs into. See the phase-19 report.
 */
@Service
public class ReconcileTransactionHandler {
    private static final Logger log = LoggerFactory.getLogger(ReconcileTransactionHandler.class);

    private final ReconciliationEngine engine;
    private final Clock clock;

    public ReconcileTransactionHandler(ReconciliationEngine engine, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param payload the {@code TransactionReceived} body, already parsed out of the envelope
     * @return the outcome to publish
     */
    public TransactionReconciledPayload reconcile(JsonNode payload) {
        MatchableEntry internal = toEntry(payload);

        List<MatchResult> results = engine.reconcile(List.of(internal), externalCandidatesFor(internal));

        // reconcile() returns one result per internal entry, so a single-entry call yields exactly
        // one. Defending against an empty list rather than indexing blindly: a silent
        // IndexOutOfBounds inside a Kafka listener is a bad way to learn the engine changed.
        MatchResult result = results.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("engine returned no result for entry " + internal.id()));

        log.debug("reconciled {} as {}", internal.id(), result.classification());

        return TransactionReconciledPayload.from(UUID.fromString(internal.id()), result, clock.instant());
    }

    /**
     * The counterparty entries this transaction could settle against.
     *
     * <p>Empty until a statement feed exists. This is the seam that feed plugs into.
     */
    private List<MatchableEntry> externalCandidatesFor(MatchableEntry internal) {
        return List.of();
    }

    private static MatchableEntry toEntry(JsonNode payload) {
        return new MatchableEntry(
                payload.path("transactionId").asText(),
                payload.path("reference").asText(),
                // amount is a decimal STRING on the wire; parsing it as a JSON number would lose
                // exactness before the engine ever sees it (ADR-0009).
                Money.of(
                        new BigDecimal(payload.path("amount").asText()),
                        CurrencyCode.of(payload.path("currency").asText())),
                LocalDate.parse(payload.path("valueDate").asText()),
                payload.path("counterpartyId").asText(),
                Side.INTERNAL);
    }
}
