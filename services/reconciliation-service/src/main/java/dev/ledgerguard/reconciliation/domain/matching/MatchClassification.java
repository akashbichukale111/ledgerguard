package dev.ledgerguard.reconciliation.domain.matching;

/**
 * The outcome of matching one entry.
 *
 * <p>{@link #isAutoResolvable()} is the line between "the engine may close this" and "a human must
 * look". Fuzzy matches are deliberately on the human side of that line however confident they look
 * — allowing a heuristic to close a financial match is the failure this engine's design rejects.
 */
public enum MatchClassification {
    AUTO_MATCHED(true),
    MATCHED_WITH_TOLERANCE(true),
    REQUIRES_REVIEW(false),
    DUPLICATE(false),
    MISSING_INTERNAL(false),
    MISSING_EXTERNAL(false),
    AMOUNT_MISMATCH(false),
    DATE_MISMATCH(false),
    UNMATCHED(false);

    private final boolean autoResolvable;

    MatchClassification(boolean autoResolvable) {
        this.autoResolvable = autoResolvable;
    }

    /** True when the engine may close the case without human review. */
    public boolean isAutoResolvable() {
        return autoResolvable;
    }

    /** Every non-auto-resolvable outcome raises a ReconciliationException for an analyst. */
    public boolean raisesException() {
        return !autoResolvable;
    }
}
