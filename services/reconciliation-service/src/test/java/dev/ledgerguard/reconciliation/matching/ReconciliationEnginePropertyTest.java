package dev.ledgerguard.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.reconciliation.domain.matching.BusinessCalendar;
import dev.ledgerguard.reconciliation.domain.matching.MatchResult;
import dev.ledgerguard.reconciliation.domain.matching.MatchableEntry;
import dev.ledgerguard.reconciliation.domain.matching.MatchingConfig;
import dev.ledgerguard.reconciliation.domain.matching.ReconciliationEngine;
import dev.ledgerguard.reconciliation.domain.matching.Side;

/**
 * Property-based tests over generated entry sets.
 *
 * <p>These exist because example-based tests cannot catch order-dependence: they always supply the
 * same input order, so a bug that only manifests when a collection iterates differently stays
 * invisible. Shuffling the input and asserting the output is unchanged is the only way to test the
 * determinism claim honestly.
 */
class ReconciliationEnginePropertyTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 7, 27);

    private static ReconciliationEngine engine() {
        return new ReconciliationEngine(MatchingConfig.defaultConfig(USD), BusinessCalendar.weekendsOnly());
    }

    /**
     * Builds a deterministic entry set from a seed, mixing exact matches, tolerance matches,
     * near-misses and orphans so the generated population exercises every branch.
     */
    private static Fixture fixture(int seed, int size) {
        Random random = new Random(seed);
        List<MatchableEntry> internal = new ArrayList<>();
        List<MatchableEntry> external = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            String reference = "INV-" + (1000 + random.nextInt(50));
            long cents = 1000 + random.nextInt(500_000);
            String amount = new java.math.BigDecimal(cents).movePointLeft(2).toPlainString();
            LocalDate date = BASE_DATE.minusDays(random.nextInt(6));

            internal.add(new MatchableEntry(
                    "i" + i, reference, Money.of(amount, USD), date, "CP-" + (i % 3), Side.INTERNAL));

            int variant = random.nextInt(4);
            switch (variant) {
                case 0 -> // exact
                    external.add(new MatchableEntry(
                            "e" + i, reference, Money.of(amount, USD), date, "CP-" + (i % 3), Side.EXTERNAL));
                case 1 -> // within absolute tolerance
                    external.add(new MatchableEntry(
                            "e" + i,
                            reference,
                            Money.of(amount, USD).plus(Money.of("0.01", USD)),
                            date,
                            "CP-" + (i % 3),
                            Side.EXTERNAL));
                case 2 -> // beyond tolerance
                    external.add(new MatchableEntry(
                            "e" + i,
                            reference,
                            Money.of(amount, USD).plus(Money.of("50.00", USD)),
                            date,
                            "CP-" + (i % 3),
                            Side.EXTERNAL));
                default -> {
                    // orphan on the external side only
                }
            }
        }
        return new Fixture(internal, external);
    }

    private record Fixture(List<MatchableEntry> internal, List<MatchableEntry> external) {}

    @Property(tries = 200)
    void shufflingTheInputDoesNotChangeTheOutput(
            @ForAll @IntRange(min = 1, max = 100_000) int seed, @ForAll @IntRange(min = 1, max = 25) int size) {

        Fixture fixture = fixture(seed, size);
        List<MatchResult> baseline = engine().reconcile(fixture.internal(), fixture.external());

        List<MatchableEntry> shuffledInternal = new ArrayList<>(fixture.internal());
        List<MatchableEntry> shuffledExternal = new ArrayList<>(fixture.external());
        Collections.shuffle(shuffledInternal, new Random(seed * 31L));
        Collections.shuffle(shuffledExternal, new Random(seed * 17L));

        List<MatchResult> shuffled = engine().reconcile(shuffledInternal, shuffledExternal);

        // Records give structural equality, so this compares classifications, matched ids AND the
        // full explanation — not merely that the same pairs were found.
        assertThat(shuffled).isEqualTo(baseline);
    }

    @Property(tries = 200)
    void runningTwiceProducesIdenticalOutput(
            @ForAll @IntRange(min = 1, max = 100_000) int seed, @ForAll @IntRange(min = 1, max = 25) int size) {
        Fixture fixture = fixture(seed, size);
        assertThat(engine().reconcile(fixture.internal(), fixture.external()))
                .isEqualTo(engine().reconcile(fixture.internal(), fixture.external()));
    }

    @Property(tries = 200)
    void noExternalEntryIsMatchedTwice(
            @ForAll @IntRange(min = 1, max = 100_000) int seed, @ForAll @IntRange(min = 1, max = 25) int size) {

        Fixture fixture = fixture(seed, size);
        List<MatchResult> results = engine().reconcile(fixture.internal(), fixture.external());

        // Double-matching is the failure mode that silently settles one statement line against two
        // postings. It must be impossible, not merely unlikely.
        List<String> claimed =
                results.stream().flatMap(r -> r.matchedEntryIds().stream()).toList();
        Set<String> unique = new HashSet<>(claimed);
        assertThat(claimed).hasSameSizeAs(unique);
    }

    @Property(tries = 200)
    void everyResultCarriesAnExplanation(
            @ForAll @IntRange(min = 1, max = 100_000) int seed, @ForAll @IntRange(min = 1, max = 25) int size) {

        Fixture fixture = fixture(seed, size);
        for (MatchResult result : engine().reconcile(fixture.internal(), fixture.external())) {
            assertThat(result.explanation()).isNotNull();
            assertThat(result.explanation().narrative()).isNotBlank();
            assertThat(result.explanation().ruleId()).isNotBlank();
            assertThat(result.explanation().ruleSetVersion()).isNotBlank();
        }
    }

    @Property(tries = 200)
    void everyInternalEntryProducesExactlyOneResult(
            @ForAll @IntRange(min = 1, max = 100_000) int seed, @ForAll @IntRange(min = 1, max = 25) int size) {

        Fixture fixture = fixture(seed, size);
        List<MatchResult> results = engine().reconcile(fixture.internal(), fixture.external());

        for (MatchableEntry entry : fixture.internal()) {
            long count =
                    results.stream().filter(r -> r.entryId().equals(entry.id())).count();
            assertThat(count)
                    .as("entry %s must yield exactly one result", entry.id())
                    .isEqualTo(1);
        }
    }

    @Property(tries = 200)
    void anAutoResolvableResultAlwaysNamesTheEntryItMatched(
            @ForAll @IntRange(min = 1, max = 100_000) int seed, @ForAll @IntRange(min = 1, max = 25) int size) {

        Fixture fixture = fixture(seed, size);
        for (MatchResult result : engine().reconcile(fixture.internal(), fixture.external())) {
            if (result.classification().isAutoResolvable()) {
                assertThat(result.matchedEntryIds())
                        .as("%s claims a match so it must name one", result.classification())
                        .isNotEmpty();
            }
        }
    }
}
