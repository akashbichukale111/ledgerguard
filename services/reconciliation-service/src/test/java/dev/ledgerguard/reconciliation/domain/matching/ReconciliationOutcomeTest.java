package dev.ledgerguard.reconciliation.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("ReconciliationOutcome")
class ReconciliationOutcomeTest {

    private static MatchResult resultOf(MatchClassification classification, List<String> matchedIds) {
        return new MatchResult(
                "entry-1",
                matchedIds,
                classification,
                new MatchExplanation("rule-1", "v1", 3, List.of(), List.of(), classification));
    }

    @ParameterizedTest
    @EnumSource(
            value = MatchClassification.class,
            names = {"AUTO_MATCHED", "MATCHED_WITH_TOLERANCE"})
    void autoResolvableClassificationsBecomeMatched(MatchClassification classification) {
        assertThat(ReconciliationOutcome.of(resultOf(classification, List.of("ext-1"))))
                .isEqualTo(ReconciliationOutcome.MATCHED);
    }

    @Test
    void aFuzzyMatchIsNeverPublishedAsMatched() {
        // MATCHED_WITH_TOLERANCE is auto-resolvable and does map to MATCHED; REQUIRES_REVIEW does
        // not, however confident it looks. Allowing a heuristic to close a financial match is the
        // failure this engine's design rejects, and the published contract has to reflect that.
        assertThat(ReconciliationOutcome.of(resultOf(MatchClassification.REQUIRES_REVIEW, List.of("ext-1"))))
                .isEqualTo(ReconciliationOutcome.REQUIRES_REVIEW);
    }

    @Test
    void aCandidateFoundButNotClosedNeedsReview() {
        assertThat(ReconciliationOutcome.of(resultOf(MatchClassification.AMOUNT_MISMATCH, List.of("ext-9"))))
                .isEqualTo(ReconciliationOutcome.REQUIRES_REVIEW);
    }

    @Test
    void nothingFoundAtAllIsUnmatched() {
        assertThat(ReconciliationOutcome.of(resultOf(MatchClassification.MISSING_EXTERNAL, List.of())))
                .isEqualTo(ReconciliationOutcome.UNMATCHED);
    }

    @ParameterizedTest
    @EnumSource(MatchClassification.class)
    void everyClassificationMapsToSomeOutcome(MatchClassification classification) {
        // A classification added to the engine must not fall through to null or throw. The mapping
        // is derived from isAutoResolvable() precisely so this stays true without a case list.
        List<String> matched = classification.isAutoResolvable() ? List.of("ext-1") : List.of();

        assertThat(ReconciliationOutcome.of(resultOf(classification, matched))).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(MatchClassification.class)
    void onlyAutoResolvableClassificationsEverProduceMatched(MatchClassification classification) {
        List<String> matched = classification.isAutoResolvable() ? List.of("ext-1") : List.of("ext-1");

        ReconciliationOutcome outcome = ReconciliationOutcome.of(resultOf(classification, matched));

        if (outcome == ReconciliationOutcome.MATCHED) {
            assertThat(classification.isAutoResolvable())
                    .as("%s produced MATCHED but is not auto-resolvable", classification)
                    .isTrue();
        }
    }
}
