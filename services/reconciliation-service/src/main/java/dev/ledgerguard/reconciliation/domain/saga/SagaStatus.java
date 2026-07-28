package dev.ledgerguard.reconciliation.domain.saga;

/** Status of a single step attempt. */
public enum SagaStatus {
    EXECUTING,
    COMPLETED,
    FAILED
}
