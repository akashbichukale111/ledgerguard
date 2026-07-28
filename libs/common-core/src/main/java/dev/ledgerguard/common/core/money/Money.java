package dev.ledgerguard.common.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact monetary amount in a single currency.
 *
 * <p>The rules below are enforced, not documented aspirations. Reasoning is in
 * {@code docs/adr/0009-monetary-representation.md}.
 *
 * <ul>
 *   <li><b>Exact.</b> {@link BigDecimal}, never {@code double}. Binary floating point cannot
 *       represent {@code 0.1}; across millions of postings that generates the very breaks this
 *       system exists to detect.
 *   <li><b>Scale comes from the currency.</b> Held at the currency's minor units. An input with
 *       more precision is <b>rejected</b>, never silently truncated — silent truncation is how
 *       money disappears.
 *   <li><b>Cross-currency arithmetic throws.</b> There is no implicit conversion. FX is out of
 *       scope for v1.
 *   <li><b>Rounding is always explicit.</b> Operations that can produce more precision than the
 *       currency allows require a {@link RoundingMode} argument. There is no default.
 *   <li><b>Equality ignores scale differences.</b> {@code BigDecimal.equals} considers {@code 2.50}
 *       and {@code 2.5} unequal; {@code Money} must not inherit that surprise.
 * </ul>
 *
 * <p>Immutable and thread-safe.
 */
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        this.amount = amount;
        this.currency = currency;
    }

    // ---------------------------------------------------------------- factories

    /**
     * Creates an amount, requiring it to already be exact at the currency's scale.
     *
     * @throws ArithmeticException if the value carries more precision than the currency permits —
     *     e.g. {@code USD 1.005}. Use {@link #of(BigDecimal, CurrencyCode, RoundingMode)} to round
     *     deliberately.
     */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        // setScale without a RoundingMode throws ArithmeticException when rounding would be
        // necessary. That is precisely the desired behaviour: reject, do not truncate.
        BigDecimal scaled;
        try {
            scaled = amount.setScale(currency.minorUnits());
        } catch (ArithmeticException e) {
            throw new ArithmeticException(String.format(
                    "amount %s has more precision than %s permits (%d minor units); "
                            + "round explicitly if that is intended",
                    amount.toPlainString(), currency.code(), currency.minorUnits()));
        }
        return new Money(scaled, currency);
    }

    /** Creates an amount from a decimal string, e.g. {@code Money.of("10.50", USD)}. */
    public static Money of(String amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        return of(new BigDecimal(amount), currency);
    }

    /**
     * Creates an amount, rounding to the currency's scale with the given mode.
     *
     * <p>Use this only for <b>derived</b> values. Stored transaction amounts are facts and must
     * never be rounded.
     */
    public static Money of(BigDecimal amount, CurrencyCode currency, RoundingMode rounding) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(rounding, "rounding mode must be explicit");
        return new Money(amount.setScale(currency.minorUnits(), rounding), currency);
    }

    /** Zero in the given currency, at that currency's scale. */
    public static Money zero(CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return new Money(BigDecimal.ZERO.setScale(currency.minorUnits()), currency);
    }

    // ---------------------------------------------------------------- accessors

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    // --------------------------------------------------------------- arithmetic

    /** @throws CurrencyMismatchException if the currencies differ */
    public Money plus(Money other) {
        requireSameCurrency(other, "add");
        return new Money(amount.add(other.amount), currency);
    }

    /** @throws CurrencyMismatchException if the currencies differ */
    public Money minus(Money other) {
        requireSameCurrency(other, "subtract");
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return isNegative() ? negated() : this;
    }

    /**
     * Multiplies by a scalar, rounding to the currency's scale.
     *
     * <p>{@code RoundingMode} is required rather than defaulted: multiplication almost always
     * produces more precision than the currency allows, and which way that rounds is a business
     * decision. {@link RoundingMode#HALF_EVEN} is the usual choice for financial aggregates because
     * it does not bias systematically upward the way {@code HALF_UP} does.
     */
    public Money multipliedBy(BigDecimal factor, RoundingMode rounding) {
        Objects.requireNonNull(factor, "factor must not be null");
        Objects.requireNonNull(rounding, "rounding mode must be explicit");
        return new Money(amount.multiply(factor).setScale(currency.minorUnits(), rounding), currency);
    }

    /** Divides by a scalar, rounding to the currency's scale. {@code RoundingMode} is required. */
    public Money dividedBy(BigDecimal divisor, RoundingMode rounding) {
        Objects.requireNonNull(divisor, "divisor must not be null");
        Objects.requireNonNull(rounding, "rounding mode must be explicit");
        if (divisor.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return new Money(amount.divide(divisor, currency.minorUnits(), rounding), currency);
    }

    // ---------------------------------------------------------------- predicates

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    // ---------------------------------------------------------------- tolerance

    /**
     * True if this and {@code other} differ by no more than {@code tolerance} in absolute terms.
     *
     * <p>Used by the reconciliation engine. Never expressed as {@code ==} or {@code
     * BigDecimal.equals}.
     *
     * @throws IllegalArgumentException if the tolerance is negative
     * @throws CurrencyMismatchException if any of the three currencies differ
     */
    public boolean isWithinAbsoluteTolerance(Money other, Money tolerance) {
        requireSameCurrency(other, "compare");
        requireSameCurrency(tolerance, "compare against tolerance");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative: " + tolerance);
        }
        return minus(other).abs().compareTo(tolerance) <= 0;
    }

    /**
     * True if this and {@code other} differ by no more than {@code basisPoints} of <b>this</b>
     * amount. One basis point is 1/100th of a percent.
     *
     * <p>The reference side matters: 5 bps of the internal amount is not 5 bps of the external
     * amount when the two differ. This method measures against the receiver, and callers are
     * expected to pick the reference deliberately.
     *
     * <p>When the receiver is zero, only an exact match passes — any non-zero deviation is
     * infinitely many basis points away.
     */
    public boolean isWithinBasisPoints(Money other, BigDecimal basisPoints) {
        requireSameCurrency(other, "compare");
        Objects.requireNonNull(basisPoints, "basisPoints must not be null");
        if (basisPoints.signum() < 0) {
            throw new IllegalArgumentException("basisPoints must not be negative: " + basisPoints);
        }
        BigDecimal delta = amount.subtract(other.amount).abs();
        if (amount.signum() == 0) {
            return delta.signum() == 0;
        }
        // Compare deltas without rounding to the currency scale: a tolerance band is a derived
        // comparison value, and rounding it first would make sub-minor-unit tolerances meaningless.
        BigDecimal allowed = amount.abs().multiply(basisPoints).movePointLeft(4);
        return delta.compareTo(allowed) <= 0;
    }

    // ---------------------------------------------------------- object contract

    /**
     * Orders by amount. Only defined within a single currency: comparing USD to EUR has no
     * meaningful answer, so it throws rather than inventing one.
     *
     * @throws CurrencyMismatchException if the currencies differ
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other, "compare");
        return amount.compareTo(other.amount);
    }

    /**
     * Value equality using {@code compareTo} semantics for the amount, so {@code 2.50} equals {@code
     * 2.5}.
     *
     * <p>Construction already normalises every amount to its currency's scale, so this is
     * belt-and-braces rather than load-bearing. It is written this way so the class stays correct
     * if a future factory forgets to normalise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Money other && currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    /**
     * Consistent with {@link #equals}: {@code stripTrailingZeros} makes numerically equal amounts
     * hash equally regardless of scale.
     */
    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    /** e.g. {@code "10.50 USD"}. Amount first, so columns of these align on the decimal point. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.code();
    }

    private void requireSameCurrency(Money other, String operation) {
        Objects.requireNonNull(other, "operand must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency, operation);
        }
    }
}
