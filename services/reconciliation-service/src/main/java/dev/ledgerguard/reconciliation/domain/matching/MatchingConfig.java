package dev.ledgerguard.reconciliation.domain.matching;

import java.math.BigDecimal;
import java.util.Objects;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;

/**
 * A versioned rule set.
 *
 * <p>The version is persisted onto every {@link MatchResult}, which is what makes "explain this
 * decision under the rules that existed at the time" true rather than aspirational. Changing any
 * value here without changing {@link #version()} silently rewrites history for every future
 * explanation of a past decision.
 *
 * @param version rule-set identifier, recorded on every result
 * @param absoluteAmountTolerance maximum absolute difference tolerated by AMOUNT_TOLERANCE_MATCH
 * @param basisPointTolerance relative tolerance, in basis points (1 bp = 0.01%)
 * @param dateToleranceBusinessDays settlement window, in BUSINESS days — not calendar days. A
 *     two-day window across a weekend is four calendar days; getting this wrong produces breaks
 *     every Monday.
 * @param fuzzyThreshold normalised similarity above which FUZZY_REFERENCE_MATCH fires. Its result
 *     is always downgraded to REQUIRES_REVIEW.
 * @param maxAggregateSize bound on N for 1:N aggregate matching. Subset-sum is combinatorially
 *     explosive; candidate sets larger than this are refused rather than attempted.
 * @param duplicateWindowDays window within which the same reference + amount + counterparty is
 *     treated as a duplicate presentation
 * @param amountBucketSize blocking-key bucket width. Wider buckets mean fewer missed matches and
 *     more comparisons; narrower means faster and more false negatives.
 */
public record MatchingConfig(
        String version,
        Money absoluteAmountTolerance,
        BigDecimal basisPointTolerance,
        int dateToleranceBusinessDays,
        double fuzzyThreshold,
        int maxAggregateSize,
        int duplicateWindowDays,
        BigDecimal amountBucketSize) {

    public MatchingConfig {
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(absoluteAmountTolerance, "absoluteAmountTolerance must not be null");
        Objects.requireNonNull(basisPointTolerance, "basisPointTolerance must not be null");
        Objects.requireNonNull(amountBucketSize, "amountBucketSize must not be null");
        if (dateToleranceBusinessDays < 0) {
            throw new IllegalArgumentException("dateToleranceBusinessDays must be >= 0");
        }
        if (fuzzyThreshold < 0.0 || fuzzyThreshold > 1.0) {
            throw new IllegalArgumentException("fuzzyThreshold must be in [0,1], got " + fuzzyThreshold);
        }
        if (maxAggregateSize < 2) {
            throw new IllegalArgumentException("maxAggregateSize must be >= 2");
        }
    }

    /** The shipped default rule set. Any change to these values requires a new version string. */
    public static MatchingConfig defaultConfig(CurrencyCode currency) {
        return new MatchingConfig(
                "ruleset-2026.07.1",
                Money.of("0.02", currency),
                new BigDecimal("5"),
                2,
                0.88,
                8,
                5,
                new BigDecimal("100"));
    }
}
