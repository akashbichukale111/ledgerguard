package dev.ledgerguard.reconciliation.domain.matching;

import java.util.List;
import java.util.Objects;

/**
 * Why the engine decided what it decided.
 *
 * <p>This is the differentiator. A confidence percentage tells an analyst nothing actionable; a
 * structured account of what was compared, by how much it differed, against which tolerance, and
 * why the runners-up lost, tells them exactly what to do next — and gives an auditor something to
 * check.
 *
 * <p>{@link #rejectedCandidates()} is the field most systems omit and the one analysts use most:
 * "why not that one?" is the second question anybody asks.
 *
 * @param ruleId which rule fired
 * @param ruleSetVersion the rule set in force, so the decision stays explainable years later
 * @param candidatePoolSize how many candidates survived blocking — distinguishes "the only option"
 *     from "one of forty"
 */
public record MatchExplanation(
        String ruleId,
        String ruleSetVersion,
        int candidatePoolSize,
        List<FieldComparison> comparisons,
        List<RejectedCandidate> rejectedCandidates,
        MatchClassification classification) {

    public MatchExplanation {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(ruleSetVersion, "ruleSetVersion must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        comparisons = List.copyOf(Objects.requireNonNull(comparisons, "comparisons must not be null"));
        rejectedCandidates =
                List.copyOf(Objects.requireNonNull(rejectedCandidates, "rejectedCandidates must not be null"));
        if (candidatePoolSize < 0) {
            throw new IllegalArgumentException("candidatePoolSize must be >= 0");
        }
    }

    /**
     * One field compared, with the actual delta and the tolerance it was judged against.
     *
     * @param delta the measured difference, rendered for display — an empty string when the notion
     *     of a delta does not apply (e.g. a currency equality check)
     * @param tolerance what the delta was compared against, or an empty string for exact matches
     */
    public record FieldComparison(
            String field, String internalValue, String externalValue, String delta, String tolerance, boolean matched) {

        public static FieldComparison exact(String field, String value, boolean matched) {
            return new FieldComparison(field, value, value, "", "", matched);
        }
    }

    /**
     * A candidate that qualified but lost, and why.
     *
     * <p>Recorded even for the winning path, because "there were three plausible matches and we
     * picked this one because its amount delta was smallest" is a materially different statement
     * from "there was exactly one match".
     */
    public record RejectedCandidate(String entryId, String reason) {}

    /**
     * A human-readable rendering, assembled from the structured fields rather than stored.
     *
     * <p>Derived rather than persisted so it can never drift from the data it describes.
     */
    public String narrative() {
        StringBuilder sb = new StringBuilder();
        sb.append(
                switch (classification) {
                    case AUTO_MATCHED -> "Matched automatically";
                    case MATCHED_WITH_TOLERANCE -> "Matched within tolerance";
                    case REQUIRES_REVIEW -> "Requires review";
                    case DUPLICATE -> "Flagged as a duplicate presentation";
                    case MISSING_INTERNAL -> "No internal entry found";
                    case MISSING_EXTERNAL -> "No external entry found";
                    case AMOUNT_MISMATCH -> "Amounts differ beyond tolerance";
                    case DATE_MISMATCH -> "Dates differ beyond the settlement window";
                    case UNMATCHED -> "No candidate found";
                });
        sb.append(" by rule ")
                .append(ruleId)
                .append(" (rule set ")
                .append(ruleSetVersion)
                .append(").");

        if (candidatePoolSize > 0) {
            sb.append(" Considered ")
                    .append(candidatePoolSize)
                    .append(candidatePoolSize == 1 ? " candidate." : " candidates.");
        }
        for (FieldComparison c : comparisons) {
            sb.append(' ').append(c.field()).append(c.matched() ? " matched" : " differed");
            if (!c.delta().isEmpty()) {
                sb.append(" by ").append(c.delta());
            }
            if (!c.tolerance().isEmpty()) {
                sb.append(" (tolerance ").append(c.tolerance()).append(')');
            }
            sb.append('.');
        }
        for (RejectedCandidate r : rejectedCandidates) {
            sb.append(" Candidate ")
                    .append(r.entryId())
                    .append(" was rejected: ")
                    .append(r.reason())
                    .append('.');
        }
        return sb.toString();
    }
}
