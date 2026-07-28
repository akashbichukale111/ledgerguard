package dev.ledgerguard.common.core.money;

/**
 * Thrown when an operation is attempted across two different currencies.
 *
 * <p>There is deliberately no implicit conversion anywhere in LedgerGuard. {@code USD 10 + EUR 5}
 * is a programming error, not a value: silently converting would produce a plausible wrong number,
 * which is worse than a loud failure. FX is out of scope for v1; see
 * {@code docs/adr/0009-monetary-representation.md}.
 */
public class CurrencyMismatchException extends RuntimeException {

    private final transient CurrencyCode left;
    private final transient CurrencyCode right;

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right, String operation) {
        super(String.format(
                "cannot %s across currencies: %s and %s. LedgerGuard performs no implicit FX conversion.",
                operation, left.code(), right.code()));
        this.left = left;
        this.right = right;
    }

    public CurrencyCode left() {
        return left;
    }

    public CurrencyCode right() {
        return right;
    }
}
