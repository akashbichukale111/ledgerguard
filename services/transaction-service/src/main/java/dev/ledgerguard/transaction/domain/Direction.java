package dev.ledgerguard.transaction.domain;

/**
 * Which way money moves.
 *
 * <p>The sign lives here, not in the amount: {@code Money} amounts on a transaction are always
 * non-negative, and a negative amount is rejected by both the domain and a database CHECK. Encoding
 * direction in the sign is a common shortcut that makes every aggregation ambiguous.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
