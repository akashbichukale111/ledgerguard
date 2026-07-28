package dev.ledgerguard.transaction.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import dev.ledgerguard.common.core.error.IllegalStateTransitionException;

/**
 * The Transaction lifecycle, with legal transitions declared as an explicit allow-table.
 *
 * <p>Declaring transitions as data rather than scattering them across {@code if} statements is what
 * makes the state machine auditable, diagrammable, and testable exhaustively. It also matters under
 * the non-blocking retry ladder ({@code docs/adr/0012-non-blocking-retry-topics.md}): a retried
 * event can arrive after later events for the same key, and rejecting the stale transition is what
 * stops it corrupting state.
 *
 * <p>The diagram in {@code docs/domain-model.md} is verified against this table by
 * {@code TransactionStatusTest}, so the two cannot drift.
 */
public enum TransactionStatus {
    RECEIVED,
    VALIDATING,
    VALIDATED,
    PROCESSING,
    RECONCILING,
    MATCHED,
    UNMATCHED,
    EXCEPTION,
    COMPENSATING,
    COMPENSATED,
    FAILED,
    COMPLETED;

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = Map.ofEntries(
            Map.entry(RECEIVED, EnumSet.of(VALIDATING, FAILED)),
            Map.entry(VALIDATING, EnumSet.of(VALIDATED, FAILED)),
            Map.entry(VALIDATED, EnumSet.of(PROCESSING, FAILED)),
            Map.entry(PROCESSING, EnumSet.of(RECONCILING, FAILED)),
            Map.entry(RECONCILING, EnumSet.of(MATCHED, UNMATCHED, EXCEPTION, FAILED)),
            Map.entry(MATCHED, EnumSet.of(COMPLETED)),
            Map.entry(UNMATCHED, EnumSet.of(COMPLETED)),
            Map.entry(EXCEPTION, EnumSet.of(COMPENSATING, COMPLETED)),
            Map.entry(COMPENSATING, EnumSet.of(COMPENSATED, FAILED)),
            // Terminal states accept nothing further.
            Map.entry(COMPENSATED, EnumSet.noneOf(TransactionStatus.class)),
            Map.entry(FAILED, EnumSet.noneOf(TransactionStatus.class)),
            Map.entry(COMPLETED, EnumSet.noneOf(TransactionStatus.class)));

    public boolean canTransitionTo(TransactionStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** @throws IllegalStateTransitionException if the transition is not in the allow-table */
    public TransactionStatus transitionTo(TransactionStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException("Transaction", this, target);
        }
        return target;
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Exposed so tests can verify the documented diagram against the actual table. */
    public Set<TransactionStatus> allowedTargets() {
        return Set.copyOf(ALLOWED.get(this));
    }
}
