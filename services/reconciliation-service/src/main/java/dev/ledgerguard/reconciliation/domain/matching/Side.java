package dev.ledgerguard.reconciliation.domain.matching;

/** Which ledger an entry came from. */
public enum Side {
    /** Our own books. */
    INTERNAL,
    /** A counterparty statement line. */
    EXTERNAL
}
