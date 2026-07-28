package dev.ledgerguard.reconciliation.domain.matching;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stage 1 — reference normalisation.
 *
 * <p>Counterparties decorate references with routing noise: {@code "REF: INV-2026-0001 /ACME"} and
 * {@code "inv 2026 0001"} are the same payment. Comparing raw strings misses that; normalising
 * first is what makes exact-reference matching useful at all.
 *
 * <p>Normalisation is applied for comparison only. The raw reference is preserved on
 * {@link MatchableEntry#rawReference()}, because an explanation must be able to show what the
 * counterparty actually sent — an analyst investigating a break needs the original, not our
 * cleaned-up version of it.
 *
 * <p>Deterministic and side-effect free: the same input always yields the same output, which the
 * determinism property test depends on.
 */
public final class ReferenceNormaliser {

    /**
     * Prefixes banks commonly prepend. Ordered longest-first so that stripping {@code "REF:"} does
     * not leave {@code "ERENCE:"} behind from {@code "REFERENCE:"}.
     */
    private static final List<String> NOISE_PREFIXES =
            List.of("REFERENCE:", "REFERENCE", "PAYMENT REF", "PMT REF", "REF:", "REF.", "REF", "INV:", "/");

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");

    private ReferenceNormaliser() {}

    /**
     * Upper-cases, strips known noise prefixes, and removes all non-alphanumeric characters.
     *
     * <p>Removing separators is the aggressive part and it is deliberate: {@code "INV-2026-0001"},
     * {@code "INV 2026 0001"} and {@code "INV/2026/0001"} are the same reference formatted by three
     * different systems. The cost is that two genuinely different references distinguished only by
     * punctuation would collide — accepted, because that pattern does not occur in payment
     * references and the alternative misses far more real matches.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String working = raw.trim().toUpperCase(Locale.ROOT);

        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : NOISE_PREFIXES) {
                if (working.startsWith(prefix)) {
                    working = working.substring(prefix.length()).trim();
                    stripped = true;
                    break;
                }
            }
        }
        return NON_ALPHANUMERIC.matcher(working).replaceAll("");
    }

    /**
     * Normalised token similarity in [0,1], used only by FUZZY_REFERENCE_MATCH.
     *
     * <p>Levenshtein distance scaled by the longer length. Chosen over a token-set measure because
     * payment references are short identifiers where transposition and single-character corruption
     * are the realistic failure modes, not word reordering.
     *
     * <p>Whatever this returns, the rule that uses it always downgrades to REQUIRES_REVIEW.
     */
    public static double similarity(String a, String b) {
        String left = normalise(a);
        String right = normalise(b);
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }
        int distance = levenshtein(left, right);
        int longest = Math.max(left.length(), right.length());
        return 1.0 - ((double) distance / longest);
    }

    /** Iterative two-row Levenshtein — O(n*m) time, O(min(n,m)) space. */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
