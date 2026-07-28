package dev.ledgerguard.reconciliation.domain.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.reconciliation.domain.matching.MatchExplanation.FieldComparison;
import dev.ledgerguard.reconciliation.domain.matching.MatchExplanation.RejectedCandidate;

/**
 * The deterministic, explainable matching engine.
 *
 * <h2>Determinism</h2>
 *
 * Same inputs plus same rule-set version produce a byte-identical outcome, always. This is not a
 * hope — {@code ReconciliationEnginePropertyTest} shuffles the input lists and asserts the output is
 * unchanged.
 *
 * <p>The two things that would break it, and how they are prevented:
 *
 * <ul>
 *   <li><b>Collection iteration order.</b> Nothing here depends on it. Candidate pools are sorted
 *       before evaluation and blocking uses a {@link TreeMap}.
 *   <li><b>Ties.</b> Ranking ends in a comparison on entry ID, which is unique, so the ordering is
 *       total and no tie is ever resolved by accident.
 * </ul>
 *
 * <h2>Explainability</h2>
 *
 * Every result carries a {@link MatchExplanation} naming the rule, the rule-set version, the
 * candidate pool size, the fields compared with their actual deltas and applied tolerances, and why
 * competing candidates lost.
 *
 * <h2>No AI anywhere in this path</h2>
 *
 * Deliberate. "The model decided" is not an answer to an auditor asking why a specific break was
 * auto-matched, and a 99%-accurate model is 1% unexplainable.
 *
 * <p>Pure domain code: no Spring, no persistence, no clock. Tests run in milliseconds and PIT can
 * mutate it cheaply.
 */
public final class ReconciliationEngine {

    private final MatchingConfig config;
    private final BusinessCalendar calendar;

    public ReconciliationEngine(MatchingConfig config, BusinessCalendar calendar) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    }

    /**
     * Reconciles internal entries against external statement entries.
     *
     * <p>Results are returned sorted by entry ID so the output order is itself deterministic — a
     * caller that persists them in order cannot introduce ordering nondeterminism either.
     *
     * @return one result per internal entry, plus one per unmatched external entry
     *     (MISSING_INTERNAL)
     */
    public List<MatchResult> reconcile(List<MatchableEntry> internal, List<MatchableEntry> external) {
        Objects.requireNonNull(internal, "internal must not be null");
        Objects.requireNonNull(external, "external must not be null");

        // Stage 2 — blocking. Sorted input in, so the pools are built identically every run.
        Map<String, List<MatchableEntry>> blocks = block(sorted(external));

        List<MatchResult> results = new ArrayList<>();
        // An external entry may be claimed by at most one internal entry. This is the invariant
        // that stops the same statement line settling two different postings, and the property
        // test asserts it directly.
        Set<String> claimed = new HashSet<>();

        for (MatchableEntry entry : sorted(internal)) {
            List<MatchableEntry> candidates = candidatesFor(entry, blocks, claimed);
            MatchResult result = evaluate(entry, candidates);
            claimed.addAll(result.matchedEntryIds());
            results.add(result);
        }

        // Stage 6 — external entries nobody claimed have no internal counterpart.
        for (MatchableEntry orphan : sorted(external)) {
            if (!claimed.contains(orphan.id())) {
                results.add(missingInternal(orphan));
            }
        }

        results.sort(Comparator.comparing(MatchResult::entryId));
        return List.copyOf(results);
    }

    // ------------------------------------------------------------------ stage 2

    /**
     * Blocking keys: currency + amount bucket.
     *
     * <p>Comparing every internal entry against every external entry is O(n*m) and unusable at
     * volume. Blocking cuts the comparison space to entries that could plausibly match.
     *
     * <p><b>The trap this originally fell into.</b> A fixed-width bucket is fundamentally
     * incompatible with a <i>relative</i> tolerance. With 100-wide buckets, USD 1,000,000.00 and
     * USD 1,000,400.00 land four buckets apart, so a pair comfortably inside the 5 bps tolerance was
     * never compared and the engine reported MISSING_EXTERNAL. Blocking was silently overriding the
     * matching rules for exactly the high-value payments where a missed match costs most.
     *
     * <p>The candidate scan therefore spans every bucket the widest applicable tolerance can reach,
     * computed from the entry's own amount. Cost now scales with the tolerance rather than being
     * fixed, which is the correct trade: a wider tolerance genuinely does mean more candidates.
     *
     * <p><b>Residual false-negative risk, stated honestly:</b> entries differing by more than the
     * tolerance are still never compared. That is intended — they could not match — but it does mean
     * a mis-keyed amount surfaces as MISSING_EXTERNAL rather than as an amount mismatch against its
     * intended counterpart.
     */
    private Map<String, List<MatchableEntry>> block(List<MatchableEntry> entries) {
        Map<String, List<MatchableEntry>> blocks = new TreeMap<>();
        for (MatchableEntry entry : entries) {
            String key = blockKey(entry.amount().currency().code(), bucketOf(entry.amount()));
            blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }
        return blocks;
    }

    private long bucketOf(Money amount) {
        return amount.amount()
                .abs()
                .divide(config.amountBucketSize(), 0, RoundingMode.FLOOR)
                .longValue();
    }

    private String blockKey(String currency, long bucket) {
        return currency + '|' + bucket;
    }

    /** The widest amount difference any rule could still accept for this entry. */
    private BigDecimal widestTolerance(Money amount) {
        BigDecimal relative =
                amount.amount().abs().multiply(config.basisPointTolerance()).movePointLeft(4);
        return relative.max(config.absoluteAmountTolerance().amount().abs());
    }

    private List<MatchableEntry> candidatesFor(
            MatchableEntry entry, Map<String, List<MatchableEntry>> blocks, Set<String> claimed) {

        BigDecimal tolerance = widestTolerance(entry.amount());
        BigDecimal magnitude = entry.amount().amount().abs();
        long lowest = magnitude
                .subtract(tolerance)
                .max(BigDecimal.ZERO)
                .divide(config.amountBucketSize(), 0, RoundingMode.FLOOR)
                .longValue();
        long highest = magnitude
                .add(tolerance)
                .divide(config.amountBucketSize(), 0, RoundingMode.FLOOR)
                .longValue();

        String currency = entry.amount().currency().code();
        List<MatchableEntry> available = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (long bucket = lowest; bucket <= highest; bucket++) {
            for (MatchableEntry candidate : blocks.getOrDefault(blockKey(currency, bucket), List.of())) {
                if (!claimed.contains(candidate.id()) && seen.add(candidate.id())) {
                    available.add(candidate);
                }
            }
        }
        return sorted(available);
    }

    // ------------------------------------------------------------ stages 3 to 5

    private MatchResult evaluate(MatchableEntry entry, List<MatchableEntry> candidates) {
        if (candidates.isEmpty()) {
            return unmatched(entry, MatchClassification.MISSING_EXTERNAL, "MISSING_ENTRY_DETECTION");
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (MatchableEntry candidate : candidates) {
            applyRules(entry, candidate).ifPresent(scored::add);
        }

        if (scored.isEmpty()) {
            return classifyNearMiss(entry, candidates);
        }

        // Stage 5 — deterministic tie-breaking. The final comparator is on a unique id, so the
        // ordering is total: shuffled input cannot change which candidate wins.
        scored.sort(Comparator.comparingInt((ScoredCandidate c) -> c.rule().precedence())
                .thenComparing(c -> c.amountDelta().amount().abs())
                .thenComparingInt(ScoredCandidate::dateDelta)
                .thenComparing(c -> c.candidate().id()));

        ScoredCandidate winner = scored.get(0);
        List<RejectedCandidate> rejected = new ArrayList<>();
        for (int i = 1; i < scored.size(); i++) {
            ScoredCandidate loser = scored.get(i);
            rejected.add(new RejectedCandidate(
                    loser.candidate().id(),
                    "rule %s ranked lower than %s (amount delta %s, date delta %d business days)"
                            .formatted(
                                    loser.rule().id(),
                                    winner.rule().id(),
                                    loser.amountDelta().abs(),
                                    loser.dateDelta())));
        }

        MatchClassification classification = winner.rule().classification();
        return new MatchResult(
                entry.id(),
                List.of(winner.candidate().id()),
                classification,
                new MatchExplanation(
                        winner.rule().id(),
                        config.version(),
                        candidates.size(),
                        winner.comparisons(),
                        rejected,
                        classification));
    }

    /** Stage 3 — the rule cascade, evaluated in precedence order; the first that fires wins. */
    private Optional<ScoredCandidate> applyRules(MatchableEntry entry, MatchableEntry candidate) {
        if (!entry.amount().currency().equals(candidate.amount().currency())) {
            // Cross-currency matching is out of scope for v1 (ADR-0009). Money would throw on the
            // comparison below, so this is refused up front rather than caught.
            return Optional.empty();
        }

        Money amountDelta = entry.amount().minus(candidate.amount());
        int dateDelta = calendar.businessDaysBetween(entry.valueDate(), candidate.valueDate());
        String internalRef = ReferenceNormaliser.normalise(entry.rawReference());
        String externalRef = ReferenceNormaliser.normalise(candidate.rawReference());
        boolean refEqual = internalRef.equals(externalRef) && !internalRef.isEmpty();
        boolean amountEqual = amountDelta.isZero();

        List<FieldComparison> comparisons = new ArrayList<>();
        comparisons.add(new FieldComparison(
                "reference",
                entry.rawReference(),
                candidate.rawReference(),
                refEqual ? "" : "normalised forms differ",
                "",
                refEqual));
        comparisons.add(new FieldComparison(
                "amount",
                entry.amount().toString(),
                candidate.amount().toString(),
                amountDelta.abs().toString(),
                config.absoluteAmountTolerance().toString(),
                amountEqual));
        comparisons.add(new FieldComparison(
                "valueDate",
                entry.valueDate().toString(),
                candidate.valueDate().toString(),
                dateDelta + " business days",
                config.dateToleranceBusinessDays() + " business days",
                dateDelta == 0));

        // DUPLICATE_DETECTION runs before the match rules: the same reference, amount and
        // counterparty presented twice is a duplicate presentation, not a match, and treating it as
        // a match would silently settle a payment twice.
        if (refEqual
                && amountEqual
                && entry.counterpartyId().equals(candidate.counterpartyId())
                && dateDelta <= config.duplicateWindowDays()
                && entry.side() == candidate.side()) {
            return Optional.of(
                    new ScoredCandidate(candidate, Rule.DUPLICATE_DETECTION, amountDelta, dateDelta, comparisons));
        }

        // EVERY match rule below is gated on the settlement window. Without that gate the original
        // REFERENCE_MATCH fired on reference + amount alone and auto-matched entries three weeks
        // apart, while DATE_TOLERANCE_MATCH sat beneath it as unreachable dead code.
        boolean withinDateWindow = dateDelta <= config.dateToleranceBusinessDays();

        if (refEqual && amountEqual && dateDelta == 0) {
            return Optional.of(new ScoredCandidate(candidate, Rule.EXACT_MATCH, amountDelta, dateDelta, comparisons));
        }
        if (refEqual && amountEqual && withinDateWindow) {
            // Same reference and amount, settled a day or two apart.
            return Optional.of(
                    new ScoredCandidate(candidate, Rule.REFERENCE_MATCH, amountDelta, dateDelta, comparisons));
        }
        if (refEqual && !amountEqual && dateDelta == 0 && withinAmountTolerance(entry.amount(), candidate.amount())) {
            return Optional.of(
                    new ScoredCandidate(candidate, Rule.AMOUNT_TOLERANCE_MATCH, amountDelta, dateDelta, comparisons));
        }
        if (refEqual
                && !amountEqual
                && dateDelta > 0
                && withinDateWindow
                && withinAmountTolerance(entry.amount(), candidate.amount())) {
            // Amount and date both drifted, each within its own tolerance.
            return Optional.of(
                    new ScoredCandidate(candidate, Rule.DATE_TOLERANCE_MATCH, amountDelta, dateDelta, comparisons));
        }
        if (amountEqual
                && withinDateWindow
                && ReferenceNormaliser.similarity(entry.rawReference(), candidate.rawReference())
                        >= config.fuzzyThreshold()) {
            // Always REQUIRES_REVIEW, never auto-matched, however high the similarity. A heuristic
            // must not close a financial match.
            return Optional.of(
                    new ScoredCandidate(candidate, Rule.FUZZY_REFERENCE_MATCH, amountDelta, dateDelta, comparisons));
        }
        return Optional.empty();
    }

    private boolean withinAmountTolerance(Money internal, Money external) {
        return internal.isWithinAbsoluteTolerance(external, config.absoluteAmountTolerance())
                || internal.isWithinBasisPoints(external, config.basisPointTolerance());
    }

    /**
     * Stage 4 — a candidate existed but no rule fired. Classifying <i>why</i> is what turns
     * "unmatched" into something an analyst can act on.
     */
    private MatchResult classifyNearMiss(MatchableEntry entry, List<MatchableEntry> candidates) {
        MatchableEntry nearest = candidates.stream()
                .filter(c -> c.amount().currency().equals(entry.amount().currency()))
                .min(Comparator.comparing((MatchableEntry c) ->
                                entry.amount().minus(c.amount()).abs().amount())
                        .thenComparing(MatchableEntry::id))
                .orElse(null);

        if (nearest == null) {
            return unmatched(entry, MatchClassification.UNMATCHED, "MISSING_ENTRY_DETECTION");
        }

        Money delta = entry.amount().minus(nearest.amount());
        int dateDelta = calendar.businessDaysBetween(entry.valueDate(), nearest.valueDate());
        boolean refEqual = ReferenceNormaliser.normalise(entry.rawReference())
                .equals(ReferenceNormaliser.normalise(nearest.rawReference()));

        MatchClassification classification;
        String ruleId;
        if (refEqual && !delta.isZero()) {
            classification = MatchClassification.AMOUNT_MISMATCH;
            ruleId = "AMOUNT_TOLERANCE_MATCH";
        } else if (refEqual) {
            classification = MatchClassification.DATE_MISMATCH;
            ruleId = "DATE_TOLERANCE_MATCH";
        } else {
            classification = MatchClassification.UNMATCHED;
            ruleId = "REFERENCE_MATCH";
        }

        List<FieldComparison> comparisons = List.of(
                new FieldComparison(
                        "reference",
                        entry.rawReference(),
                        nearest.rawReference(),
                        refEqual ? "" : "normalised forms differ",
                        "",
                        refEqual),
                new FieldComparison(
                        "amount",
                        entry.amount().toString(),
                        nearest.amount().toString(),
                        delta.abs().toString(),
                        config.absoluteAmountTolerance().toString(),
                        delta.isZero()),
                new FieldComparison(
                        "valueDate",
                        entry.valueDate().toString(),
                        nearest.valueDate().toString(),
                        dateDelta + " business days",
                        config.dateToleranceBusinessDays() + " business days",
                        dateDelta <= config.dateToleranceBusinessDays()));

        return new MatchResult(
                entry.id(),
                List.of(),
                classification,
                new MatchExplanation(
                        ruleId,
                        config.version(),
                        candidates.size(),
                        comparisons,
                        List.of(new RejectedCandidate(
                                nearest.id(), "closest candidate but outside the configured tolerances")),
                        classification));
    }

    private MatchResult unmatched(MatchableEntry entry, MatchClassification classification, String ruleId) {
        return new MatchResult(
                entry.id(),
                List.of(),
                classification,
                new MatchExplanation(ruleId, config.version(), 0, List.of(), List.of(), classification));
    }

    private MatchResult missingInternal(MatchableEntry external) {
        return new MatchResult(
                external.id(),
                List.of(),
                MatchClassification.MISSING_INTERNAL,
                new MatchExplanation(
                        "MISSING_ENTRY_DETECTION",
                        config.version(),
                        0,
                        List.of(),
                        List.of(),
                        MatchClassification.MISSING_INTERNAL));
    }

    /** Sorting by unique id is what makes the whole pipeline order-independent. */
    private static List<MatchableEntry> sorted(List<MatchableEntry> entries) {
        List<MatchableEntry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparing(MatchableEntry::id));
        return copy;
    }

    /** Rules in precedence order. Lower precedence wins ties (stage 5, criterion 1). */
    enum Rule {
        EXACT_MATCH(1, MatchClassification.AUTO_MATCHED),
        DUPLICATE_DETECTION(2, MatchClassification.DUPLICATE),
        REFERENCE_MATCH(3, MatchClassification.AUTO_MATCHED),
        AMOUNT_TOLERANCE_MATCH(4, MatchClassification.MATCHED_WITH_TOLERANCE),
        DATE_TOLERANCE_MATCH(5, MatchClassification.MATCHED_WITH_TOLERANCE),
        FUZZY_REFERENCE_MATCH(6, MatchClassification.REQUIRES_REVIEW);

        private final int precedence;
        private final MatchClassification classification;

        Rule(int precedence, MatchClassification classification) {
            this.precedence = precedence;
            this.classification = classification;
        }

        int precedence() {
            return precedence;
        }

        MatchClassification classification() {
            return classification;
        }

        String id() {
            return name();
        }
    }

    private record ScoredCandidate(
            MatchableEntry candidate, Rule rule, Money amountDelta, int dateDelta, List<FieldComparison> comparisons) {}

    /** Exposed for the aggregate-match bound; see {@link MatchingConfig#maxAggregateSize()}. */
    public BigDecimal amountBucketSize() {
        return config.amountBucketSize();
    }
}
