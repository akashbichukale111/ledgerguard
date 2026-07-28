package dev.ledgerguard.reconciliation.domain.matching;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The payload of {@code TransactionReconciled} v1.
 *
 * <p>Mirrors {@code schemas/TransactionReconciled/v1.json}. Built from a {@link MatchResult} rather
 * than assembled by hand at the call site, so the published outcome cannot drift from the decision
 * the engine actually made.
 *
 * <p>The explanation fields travel with the outcome deliberately. A consumer that can see
 * {@code UNMATCHED} but not which rule fired, against how large a candidate pool, cannot act on it
 * — and neither can an auditor reading it back years later.
 */
public record TransactionReconciledPayload(
        UUID transactionId,
        ReconciliationOutcome outcome,
        MatchClassification classification,
        List<String> matchedEntryIds,
        String ruleId,
        String ruleSetVersion,
        int candidatePoolSize,
        Instant reconciledAt) {

    public TransactionReconciledPayload {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(ruleSetVersion, "ruleSetVersion must not be null");
        Objects.requireNonNull(reconciledAt, "reconciledAt must not be null");
        matchedEntryIds = List.copyOf(Objects.requireNonNull(matchedEntryIds, "matchedEntryIds must not be null"));
        if (candidatePoolSize < 0) {
            throw new IllegalArgumentException("candidatePoolSize must be >= 0, got " + candidatePoolSize);
        }
    }

    /** Derives the published outcome from the engine's decision. */
    public static TransactionReconciledPayload from(UUID transactionId, MatchResult result, Instant reconciledAt) {
        MatchExplanation explanation = result.explanation();
        return new TransactionReconciledPayload(
                transactionId,
                ReconciliationOutcome.of(result),
                result.classification(),
                result.matchedEntryIds(),
                explanation.ruleId(),
                explanation.ruleSetVersion(),
                explanation.candidatePoolSize(),
                reconciledAt);
    }
}
