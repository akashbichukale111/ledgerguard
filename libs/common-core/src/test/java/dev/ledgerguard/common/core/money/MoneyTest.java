package dev.ledgerguard.common.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    @Nested
    @DisplayName("scale is derived from the currency, not assumed to be 2")
    class Scale {

        @ParameterizedTest(name = "{0} has {1} minor units")
        @CsvSource({"USD,2", "EUR,2", "GBP,2", "JPY,0", "BHD,3", "KWD,3", "OMR,3"})
        void minorUnitsComeFromIso4217(String code, int expected) {
            assertThat(CurrencyCode.of(code).minorUnits()).isEqualTo(expected);
        }

        @Test
        void amountIsHeldAtTheCurrencyScale() {
            assertThat(Money.of("10", USD).amount().scale()).isEqualTo(2);
            assertThat(Money.of("10", JPY).amount().scale()).isZero();
            assertThat(Money.of("10", BHD).amount().scale()).isEqualTo(3);
        }

        @Test
        void zeroIsScaledToo() {
            assertThat(Money.zero(JPY).amount().toPlainString()).isEqualTo("0");
            assertThat(Money.zero(USD).amount().toPlainString()).isEqualTo("0.00");
            assertThat(Money.zero(BHD).amount().toPlainString()).isEqualTo("0.000");
        }
    }

    @Nested
    @DisplayName("excess precision is rejected, never silently truncated")
    class PrecisionRejection {

        @Test
        void usdRejectsAThirdDecimal() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of("1.005", USD))
                    .withMessageContaining("more precision than USD permits");
        }

        @Test
        void jpyRejectsAnyFraction() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of("100.5", JPY))
                    .withMessageContaining("more precision than JPY permits");
        }

        @Test
        void trailingZeroesAreNotExcessPrecision() {
            // 1.5000 is exactly 1.50 at USD scale — no information is lost, so it is accepted.
            assertThat(Money.of("1.5000", USD)).isEqualTo(Money.of("1.50", USD));
        }

        @Test
        void explicitRoundingIsAvailableWhenIntended() {
            Money rounded = Money.of(new BigDecimal("1.005"), USD, RoundingMode.HALF_EVEN);
            // HALF_EVEN on an exact .005 tie rounds to the even digit: 1.00, not 1.01.
            assertThat(rounded.amount().toPlainString()).isEqualTo("1.00");
        }
    }

    @Nested
    @DisplayName("cross-currency arithmetic throws — there is no implicit FX")
    class CurrencyIsolation {

        @Test
        void additionAcrossCurrenciesThrows() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of("10.00", USD).plus(Money.of("5.00", EUR)))
                    .withMessageContaining("no implicit FX conversion");
        }

        @Test
        void subtractionAcrossCurrenciesThrows() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of("10.00", USD).minus(Money.of("5.00", EUR)));
        }

        @Test
        void comparisonAcrossCurrenciesThrows() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of("10.00", USD).compareTo(Money.of("5.00", EUR)));
        }

        @Test
        void equalityAcrossCurrenciesIsFalseRatherThanThrowing() {
            // equals must never throw — collections rely on it. Different currency, not equal.
            assertThat(Money.of("10.00", USD)).isNotEqualTo(Money.of("10.00", EUR));
        }
    }

    @Nested
    @DisplayName("equality uses compareTo semantics, not BigDecimal.equals")
    class Equality {

        @Test
        void scaleDifferencesDoNotBreakEquality() {
            // The classic BigDecimal trap: new BigDecimal("2.50").equals(new BigDecimal("2.5"))
            // is false. Money must not inherit that.
            assertThat(new BigDecimal("2.50")).isNotEqualTo(new BigDecimal("2.5"));
            assertThat(Money.of("2.50", USD)).isEqualTo(Money.of("2.5", USD));
        }

        @Test
        void hashCodeIsConsistentWithEquals() {
            assertThat(Money.of("2.50", USD)).hasSameHashCodeAs(Money.of("2.5", USD));
        }

        @Test
        void zeroSignsAreEqual() {
            assertThat(Money.of("-0.00", USD)).isEqualTo(Money.of("0.00", USD));
            assertThat(Money.of("-0.00", USD)).hasSameHashCodeAs(Money.of("0.00", USD));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void additionAndSubtractionAreExact() {
            // The canonical floating-point failure: 0.1 + 0.2 != 0.3 in IEEE-754.
            assertThat(Money.of("0.10", USD).plus(Money.of("0.20", USD))).isEqualTo(Money.of("0.30", USD));
        }

        @Test
        void repeatedAdditionDoesNotDrift() {
            Money total = Money.zero(USD);
            for (int i = 0; i < 1000; i++) {
                total = total.plus(Money.of("0.01", USD));
            }
            assertThat(total).isEqualTo(Money.of("10.00", USD));
        }

        @Test
        void negativeAmountsAreSupported() {
            // Credits are negative postings; this is not an error case.
            Money credit = Money.of("-42.50", USD);
            assertThat(credit.isNegative()).isTrue();
            assertThat(credit.abs()).isEqualTo(Money.of("42.50", USD));
            assertThat(credit.negated()).isEqualTo(Money.of("42.50", USD));
        }

        @Test
        void multiplicationRequiresAnExplicitRoundingMode() {
            Money result = Money.of("10.00", USD).multipliedBy(new BigDecimal("0.075"), RoundingMode.HALF_EVEN);
            assertThat(result).isEqualTo(Money.of("0.75", USD));
        }

        @Test
        void halfEvenDoesNotBiasUpwardTheWayHalfUpDoes() {
            // Both are exact .5 ties at USD scale. HALF_EVEN sends one down and one up,
            // so a population of ties cancels instead of drifting upward.
            Money a = Money.of(new BigDecimal("0.125"), USD, RoundingMode.HALF_EVEN);
            Money b = Money.of(new BigDecimal("0.135"), USD, RoundingMode.HALF_EVEN);
            assertThat(a.amount().toPlainString()).isEqualTo("0.12");
            assertThat(b.amount().toPlainString()).isEqualTo("0.14");

            Money halfUp = Money.of(new BigDecimal("0.125"), USD, RoundingMode.HALF_UP);
            assertThat(halfUp.amount().toPlainString()).isEqualTo("0.13");
        }

        @Test
        void divisionByZeroThrows() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of("10.00", USD).dividedBy(BigDecimal.ZERO, RoundingMode.HALF_EVEN))
                    .withMessage("division by zero");
        }

        @Test
        void veryLargeAmountsDoNotOverflow() {
            // A long of minor units would overflow here; BigDecimal does not.
            Money huge = Money.of("999999999999999.99", USD);
            assertThat(huge.plus(Money.of("0.01", USD))).isEqualTo(Money.of("1000000000000000.00", USD));
        }
    }

    @Nested
    @DisplayName("tolerance comparisons")
    class Tolerance {

        @Test
        void absoluteToleranceIsInclusiveAtTheBoundary() {
            Money internal = Money.of("100.00", USD);
            Money external = Money.of("100.02", USD);
            assertThat(internal.isWithinAbsoluteTolerance(external, Money.of("0.02", USD)))
                    .isTrue();
            assertThat(internal.isWithinAbsoluteTolerance(external, Money.of("0.01", USD)))
                    .isFalse();
        }

        @Test
        void absoluteToleranceIsSymmetric() {
            Money a = Money.of("100.00", USD);
            Money b = Money.of("100.02", USD);
            Money tol = Money.of("0.02", USD);
            assertThat(a.isWithinAbsoluteTolerance(b, tol)).isEqualTo(b.isWithinAbsoluteTolerance(a, tol));
        }

        @Test
        void negativeToleranceIsRejected() {
            assertThatThrownBy(() -> Money.of("1.00", USD)
                            .isWithinAbsoluteTolerance(Money.of("1.00", USD), Money.of("-0.01", USD)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");
        }

        @Test
        void basisPointToleranceIsRelativeToTheReceiver() {
            // 5 bps of 10,000.00 is 5.00
            Money reference = Money.of("10000.00", USD);
            assertThat(reference.isWithinBasisPoints(Money.of("10005.00", USD), new BigDecimal("5")))
                    .isTrue();
            assertThat(reference.isWithinBasisPoints(Money.of("10005.01", USD), new BigDecimal("5")))
                    .isFalse();
        }

        @Test
        void basisPointToleranceAgainstZeroRequiresExactness() {
            Money zero = Money.zero(USD);
            assertThat(zero.isWithinBasisPoints(Money.zero(USD), new BigDecimal("100")))
                    .isTrue();
            assertThat(zero.isWithinBasisPoints(Money.of("0.01", USD), new BigDecimal("100")))
                    .isFalse();
        }

        @Test
        void zeroToleranceMeansExactMatch() {
            Money a = Money.of("10.00", USD);
            assertThat(a.isWithinAbsoluteTolerance(Money.of("10.00", USD), Money.zero(USD)))
                    .isTrue();
            assertThat(a.isWithinAbsoluteTolerance(Money.of("10.01", USD), Money.zero(USD)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("currency codes")
    class Currencies {

        @ParameterizedTest
        @ValueSource(strings = {"US", "USDD", "ZZZ", "123"})
        void invalidCodesAreRejected(String code) {
            assertThatThrownBy(() -> CurrencyCode.of(code)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void codesAreNormalisedToUpperCase() {
            assertThat(CurrencyCode.of("usd")).isEqualTo(USD);
            assertThat(CurrencyCode.of(" usd ")).isEqualTo(USD);
        }

        @Test
        void currenciesWithoutMinorUnitsAreRejected() {
            // XAU (gold) reports -1 fraction digits. An amount whose scale is undefined cannot be
            // stored meaningfully, so it is refused at construction rather than later.
            assertThatThrownBy(() -> CurrencyCode.of("XAU"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no defined minor units");
        }

        @Test
        void nullIsRejected() {
            assertThatThrownBy(() -> CurrencyCode.of(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("representation")
    class Representation {

        @Test
        void toStringPutsTheAmountFirstSoColumnsAlign() {
            assertThat(Money.of("10.50", USD)).hasToString("10.50 USD");
            assertThat(Money.of("100", JPY)).hasToString("100 JPY");
        }

        @Test
        void plainStringNeverUsesScientificNotation() {
            // toString on BigDecimal can yield "1E+3"; a money value must never render that way.
            assertThat(Money.of(new BigDecimal("1E+3"), USD).toString()).isEqualTo("1000.00 USD");
        }

        @Test
        void constructionIsSideEffectFree() {
            BigDecimal input = new BigDecimal("10.5");
            assertThatCode(() -> Money.of(input, USD)).doesNotThrowAnyException();
            assertThat(input.toPlainString()).isEqualTo("10.5");
        }
    }
}
