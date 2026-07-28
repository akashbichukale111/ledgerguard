package dev.ledgerguard.reconciliation.domain.matching;

import java.util.List;
import java.util.Objects;

/**
 * The outcome for one internal entry.
 *
 * <p>{@code matchedEntryIds} is a list because 1:N aggregate matching is a real case — one
 * statement line settling several ledger postings.
 *
 * <p>An explanation is mandatory, not optional: a decision without a reason is not a decision this
 * system is willing to make. The property test asserts the explanation is never empty.
 */
public record MatchResult(
        String entryId,
        List<String> matchedEntryIds,
        MatchClassification classification,
        MatchExplanation explanation) {

    public MatchResult {
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(explanation, "explanation must not be null");
        matchedEntryIds = List.copyOf(Objects.requireNonNull(matchedEntryIds, "matchedEntryIds must not be null"));

        if (classification.isAutoResolvable() && matchedEntryIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "classification " + classification + " claims a match but names no matched entries");
        }
    }

    public boolean isMatched() {
        return !matchedEntryIds.isEmpty();
    }
}
