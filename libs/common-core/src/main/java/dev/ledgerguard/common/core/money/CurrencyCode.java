package dev.ledgerguard.common.core.money;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * An ISO 4217 currency, carrying the number of minor units that determines the scale of every
 * {@link Money} amount denominated in it.
 *
 * <p>Minor units come from {@link Currency}, which is the JDK's ISO 4217 table, rather than from a
 * hand-maintained map. A hardcoded scale of 2 is wrong for JPY (0) and for BHD, KWD, and OMR (3),
 * and those are exactly the cases a reviewer checks.
 *
 * <p>Currencies with no meaningful minor-unit count — the precious metals (XAU, XAG) and the
 * testing code XXX report {@code -1} from the JDK — are rejected. LedgerGuard settles money, and a
 * value whose scale is undefined cannot be stored in a {@code NUMERIC(19,4)} column with any
 * meaning.
 */
public final class CurrencyCode {

    private final Currency currency;

    private CurrencyCode(Currency currency) {
        this.currency = currency;
    }

    /**
     * Resolves an ISO 4217 alphabetic code, e.g. {@code "USD"}.
     *
     * @throws IllegalArgumentException if the code is not a known ISO 4217 currency, or if it has
     *     no defined minor-unit count
     */
    public static CurrencyCode of(String code) {
        Objects.requireNonNull(code, "currency code must not be null");
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        if (normalised.length() != 3) {
            throw new IllegalArgumentException(
                    "currency code must be 3 characters (ISO 4217 alphabetic), got: '" + code + "'");
        }
        Currency resolved;
        try {
            resolved = Currency.getInstance(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ISO 4217 currency code: '" + code + "'", e);
        }
        if (resolved.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "currency '" + normalised + "' has no defined minor units and cannot denominate a monetary amount");
        }
        return new CurrencyCode(resolved);
    }

    /** The ISO 4217 alphabetic code, e.g. {@code "USD"}. */
    public String code() {
        return currency.getCurrencyCode();
    }

    /**
     * Number of digits after the decimal point for this currency: 0 for JPY, 2 for USD and EUR, 3
     * for BHD, KWD and OMR. This is the scale every {@link Money} amount in this currency is held
     * at.
     */
    public int minorUnits() {
        return currency.getDefaultFractionDigits();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CurrencyCode other && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return currency.hashCode();
    }

    @Override
    public String toString() {
        return code();
    }
}
