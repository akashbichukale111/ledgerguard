package dev.ledgerguard.reconciliation.domain.matching;

/**
 * The coarse business outcome published on {@code TransactionReconciled}.
 *
 * <p>Deliberately narrower than {@link MatchClassification}: a consumer building a dashboard should
 * not have to know all nine engine classifications to answer "did this settle". The full
 * classification travels alongside for anyone who does need it.
 *
 * <p>The mapping is derived from {@link MatchClassification#isAutoResolvable()} rather than written
 * out case by case. That matters: adding a classification to the engine cannot silently produce an
 * outcome nobody chose, and the line between "the engine may close this" and "a human must look"
 * stays defined in exactly one place.
 */
public enum ReconciliationOutcome {
    /** The engine closed it. Only auto-resolvable classifications reach here. */
    MATCHED,

    /** A counterpart was found or suspected, but a human must confirm. */
    REQUIRES_REVIEW,

    /** Nothing to match against. */
    UNMATCHED;

    public static ReconciliationOutcome of(MatchResult result) {
        if (result.classification().isAutoResolvable()) {
            // isAutoResolvable() already guarantees matchedEntryIds is non-empty — MatchResult's
            // constructor rejects a classification that claims a match while naming none.
            return MATCHED;
        }
        // A candidate was found but the engine will not close on it, which is the case an analyst
        // must actually look at. Nothing found at all is a different, cheaper conversation.
        return result.isMatched() ? REQUIRES_REVIEW : UNMATCHED;
    }
}
