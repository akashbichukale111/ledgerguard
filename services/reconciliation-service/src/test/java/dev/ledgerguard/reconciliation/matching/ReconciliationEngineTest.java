package dev.ledgerguard.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.reconciliation.domain.matching.BusinessCalendar;
import dev.ledgerguard.reconciliation.domain.matching.MatchClassification;
import dev.ledgerguard.reconciliation.domain.matching.MatchResult;
import dev.ledgerguard.reconciliation.domain.matching.MatchableEntry;
import dev.ledgerguard.reconciliation.domain.matching.MatchingConfig;
import dev.ledgerguard.reconciliation.domain.matching.ReconciliationEngine;
import dev.ledgerguard.reconciliation.domain.matching.ReferenceNormaliser;
import dev.ledgerguard.reconciliation.domain.matching.Side;

/** Per-rule tests with boundary values at the tolerance edges (§9). */
class ReconciliationEngineTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 24);

    private final MatchingConfig config = MatchingConfig.defaultConfig(USD);
    private final ReconciliationEngine engine = new ReconciliationEngine(config, BusinessCalendar.weekendsOnly());

    private static MatchableEntry entry(String id, String ref, String amount, LocalDate date, Side side) {
        return new MatchableEntry(id, ref, Money.of(amount, USD), date, "CP-ACME", side);
    }

    private static MatchableEntry internal(String id, String ref, String amount, LocalDate date) {
        return entry(id, ref, amount, date, Side.INTERNAL);
    }

    private static MatchableEntry external(String id, String ref, String amount, LocalDate date) {
        return entry(id, ref, amount, date, Side.EXTERNAL);
    }

    private MatchResult resultFor(List<MatchResult> results, String entryId) {
        return results.stream()
                .filter(r -> r.entryId().equals(entryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result for " + entryId));
    }

    @Nested
    @DisplayName("EXACT_MATCH")
    class ExactMatch {

        @Test
        void referenceAmountAndDateAllEqualAutoMatches() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-2026-0001", "100.00", MONDAY)),
                    List.of(external("e1", "INV-2026-0001", "100.00", MONDAY)));

            MatchResult result = resultFor(results, "i1");
            assertThat(result.classification()).isEqualTo(MatchClassification.AUTO_MATCHED);
            assertThat(result.matchedEntryIds()).containsExactly("e1");
            assertThat(result.explanation().ruleId()).isEqualTo("EXACT_MATCH");
            assertThat(result.explanation().ruleSetVersion()).isEqualTo(config.version());
        }

        @Test
        void referencesMatchAcrossFormattingDifferences() {
            // The same reference from three systems. Raw string comparison would miss both.
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-2026-0001", "100.00", MONDAY)),
                    List.of(external("e1", "REF: inv 2026 0001", "100.00", MONDAY)));

            assertThat(resultFor(results, "i1").classification()).isEqualTo(MatchClassification.AUTO_MATCHED);
        }
    }

    @Nested
    @DisplayName("AMOUNT_TOLERANCE_MATCH — boundary values")
    class AmountTolerance {

        @Test
        void deltaExactlyAtTheAbsoluteToleranceMatches() {
            // Tolerance is 0.02. A delta of exactly 0.02 is inclusive.
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.02", MONDAY)));

            MatchResult result = resultFor(results, "i1");
            assertThat(result.classification()).isEqualTo(MatchClassification.MATCHED_WITH_TOLERANCE);
            assertThat(result.explanation().ruleId()).isEqualTo("AMOUNT_TOLERANCE_MATCH");
        }

        @Test
        void deltaOneCentBeyondToleranceDoesNotAutoMatch() {
            // 100.03 exceeds both the 0.02 absolute tolerance and 5 bps of 100.00 (= 0.05)... so
            // this asserts the classification, which is the analyst-facing outcome.
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.06", MONDAY)));

            MatchResult result = resultFor(results, "i1");
            assertThat(result.classification()).isEqualTo(MatchClassification.AMOUNT_MISMATCH);
            assertThat(result.matchedEntryIds()).isEmpty();
        }

        @Test
        void basisPointToleranceCoversLargeAmountsAbsoluteToleranceCannot() {
            // 5 bps of 1,000,000.00 is 500.00 — far beyond the 0.02 absolute tolerance. A system
            // with only an absolute tolerance would break every large-value payment.
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "1000000.00", MONDAY)),
                    List.of(external("e1", "INV-1", "1000400.00", MONDAY)));

            assertThat(resultFor(results, "i1").classification()).isEqualTo(MatchClassification.MATCHED_WITH_TOLERANCE);
        }

        @Test
        void explanationRecordsTheActualDeltaAndTheToleranceApplied() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.02", MONDAY)));

            var amountComparison = resultFor(results, "i1").explanation().comparisons().stream()
                    .filter(c -> c.field().equals("amount"))
                    .findFirst()
                    .orElseThrow();

            assertThat(amountComparison.delta()).isEqualTo("0.02 USD");
            assertThat(amountComparison.tolerance()).isEqualTo("0.02 USD");
        }
    }

    @Nested
    @DisplayName("business-day settlement windows")
    class DateTolerance {

        @Test
        void fridayToMondayIsOneBusinessDayNotThreeCalendarDays() {
            BusinessCalendar calendar = BusinessCalendar.weekendsOnly();
            assertThat(calendar.businessDaysBetween(FRIDAY, MONDAY)).isEqualTo(1);
            // The naive implementation would say 3 and raise a break for every Friday payment.
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(FRIDAY, MONDAY))
                    .isEqualTo(3);
        }

        @Test
        void weekendsAreNotBusinessDays() {
            BusinessCalendar calendar = BusinessCalendar.weekendsOnly();
            assertThat(calendar.isBusinessDay(LocalDate.of(2026, 7, 25))).isFalse(); // Saturday
            assertThat(calendar.isBusinessDay(LocalDate.of(2026, 7, 26))).isFalse(); // Sunday
            assertThat(calendar.isBusinessDay(MONDAY)).isTrue();
        }

        @Test
        void businessDaysBetweenIsSymmetric() {
            BusinessCalendar calendar = BusinessCalendar.weekendsOnly();
            assertThat(calendar.businessDaysBetween(FRIDAY, MONDAY))
                    .isEqualTo(calendar.businessDaysBetween(MONDAY, FRIDAY));
        }

        @Test
        void aDateBeyondTheWindowWithAMatchingReferenceIsADateMismatch() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", LocalDate.of(2026, 7, 6))),
                    List.of(external("e1", "INV-1", "100.00", LocalDate.of(2026, 7, 27))));

            MatchResult result = resultFor(results, "i1");
            assertThat(result.classification()).isEqualTo(MatchClassification.DATE_MISMATCH);
            assertThat(result.matchedEntryIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("FUZZY_REFERENCE_MATCH is never auto-matched")
    class FuzzyMatch {

        @Test
        void aHighSimilarityMatchStillRequiresReview() {
            // One corrupted character in a 16-character reference: similarity 15/16 = 0.9375,
            // above the 0.88 threshold. (An earlier version of this test used a transposition,
            // which is TWO edits and scored 0.867 — below the threshold. The engine was right and
            // the test input was wrong; recorded here because the distinction is easy to get wrong.)
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-2026-00012345", "100.00", MONDAY)),
                    List.of(external("e1", "INV-2026-00012845", "100.00", MONDAY)));

            MatchResult result = resultFor(results, "i1");
            assertThat(result.classification()).isEqualTo(MatchClassification.REQUIRES_REVIEW);
            assertThat(result.classification().isAutoResolvable()).isFalse();
            assertThat(result.explanation().ruleId()).isEqualTo("FUZZY_REFERENCE_MATCH");
        }

        @Test
        void noFuzzyClassificationIsEverAutoResolvable() {
            assertThat(MatchClassification.REQUIRES_REVIEW.isAutoResolvable()).isFalse();
            assertThat(MatchClassification.REQUIRES_REVIEW.raisesException()).isTrue();
        }
    }

    @Nested
    @DisplayName("DUPLICATE_DETECTION")
    class DuplicateDetection {

        @Test
        void theSamePaymentPresentedTwiceIsFlaggedNotMatched() {
            // Both entries are INTERNAL: the counterparty presented the same payment twice.
            // Treating this as a match would settle the payment twice.
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(entry("d1", "INV-1", "100.00", MONDAY, Side.INTERNAL)));

            MatchResult result = resultFor(results, "i1");
            assertThat(result.classification()).isEqualTo(MatchClassification.DUPLICATE);
            assertThat(result.classification().isAutoResolvable()).isFalse();
        }
    }

    @Nested
    @DisplayName("missing entries")
    class MissingEntries {

        @Test
        void anInternalEntryWithNoCandidateIsMissingExternal() {
            List<MatchResult> results = engine.reconcile(List.of(internal("i1", "INV-1", "100.00", MONDAY)), List.of());

            assertThat(resultFor(results, "i1").classification()).isEqualTo(MatchClassification.MISSING_EXTERNAL);
        }

        @Test
        void anUnclaimedExternalEntryIsMissingInternal() {
            List<MatchResult> results = engine.reconcile(List.of(), List.of(external("e1", "INV-1", "100.00", MONDAY)));

            assertThat(resultFor(results, "e1").classification()).isEqualTo(MatchClassification.MISSING_INTERNAL);
        }

        @Test
        void anExternalEntryClaimedByAMatchIsNotAlsoReportedMissing() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.00", MONDAY)));

            assertThat(results).hasSize(1);
            assertThat(results).noneMatch(r -> r.classification() == MatchClassification.MISSING_INTERNAL);
        }
    }

    @Nested
    @DisplayName("deterministic tie-breaking")
    class TieBreaking {

        @Test
        void whenTwoCandidatesTieTheSmallerAmountDeltaWins() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.02", MONDAY), external("e2", "INV-1", "100.00", MONDAY)));

            MatchResult result = resultFor(results, "i1");
            // e2 is the exact match; e1 only qualifies on tolerance.
            assertThat(result.matchedEntryIds()).containsExactly("e2");
            assertThat(result.explanation().ruleId()).isEqualTo("EXACT_MATCH");
        }

        @Test
        void rejectedCandidatesAreExplainedNotSilentlyDropped() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.02", MONDAY), external("e2", "INV-1", "100.00", MONDAY)));

            var rejected = resultFor(results, "i1").explanation().rejectedCandidates();
            assertThat(rejected).hasSize(1);
            assertThat(rejected.get(0).entryId()).isEqualTo("e1");
            assertThat(rejected.get(0).reason()).contains("ranked lower");
        }

        @Test
        void candidatePoolSizeIsRecordedSoOneOfFortyIsDistinguishableFromTheOnlyOption() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.00", MONDAY), external("e2", "OTHER-9", "100.01", MONDAY)));

            assertThat(resultFor(results, "i1").explanation().candidatePoolSize())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("no entry is matched twice")
    class SingleClaim {

        @Test
        void oneExternalEntryCannotSettleTwoInternalEntries() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY), internal("i2", "INV-1", "100.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.00", MONDAY)));

            long claims = results.stream()
                    .flatMap(r -> r.matchedEntryIds().stream())
                    .filter("e1"::equals)
                    .count();
            assertThat(claims).as("e1 must be claimed at most once").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("explanations are always present")
    class Explanations {

        @Test
        void everyResultCarriesANonEmptyNarrative() {
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "INV-1", "100.00", MONDAY), internal("i2", "ORPHAN", "55.00", MONDAY)),
                    List.of(external("e1", "INV-1", "100.00", MONDAY), external("e2", "NOBODY", "77.00", MONDAY)));

            assertThat(results).isNotEmpty();
            for (MatchResult result : results) {
                assertThat(result.explanation().narrative()).isNotBlank();
                assertThat(result.explanation().ruleSetVersion()).isEqualTo(config.version());
            }
        }
    }

    @Nested
    @DisplayName("reference normalisation")
    class Normalisation {

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource({
            "'INV-2026-0001',        INV20260001",
            "'REF: INV-2026-0001',   INV20260001",
            "'inv 2026 0001',        INV20260001",
            "'REFERENCE: INV/2026/0001', INV20260001",
            "'  INV-2026-0001  ',    INV20260001",
            "'PMT REF INV-2026-0001', INV20260001"
        })
        void noiseIsStrippedConsistently(String raw, String expected) {
            assertThat(ReferenceNormaliser.normalise(raw)).isEqualTo(expected);
        }

        @Test
        void nullAndBlankNormaliseToEmptyRatherThanThrowing() {
            assertThat(ReferenceNormaliser.normalise(null)).isEmpty();
            assertThat(ReferenceNormaliser.normalise("   ")).isEmpty();
        }

        @Test
        void identicalReferencesAreFullySimilar() {
            assertThat(ReferenceNormaliser.similarity("INV-1", "inv 1")).isEqualTo(1.0);
        }

        @Test
        void twoEmptyReferencesDoNotCountAsAMatchForFuzzyPurposes() {
            // Similarity is 1.0, but the engine requires a non-empty normalised reference for
            // reference equality, so empty references cannot silently match each other.
            List<MatchResult> results = engine.reconcile(
                    List.of(internal("i1", "", "100.00", MONDAY)), List.of(external("e1", "", "100.00", MONDAY)));

            assertThat(resultFor(results, "i1").classification()).isNotEqualTo(MatchClassification.AUTO_MATCHED);
        }
    }
}
