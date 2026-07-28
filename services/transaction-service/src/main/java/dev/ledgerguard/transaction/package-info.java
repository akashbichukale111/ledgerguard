/**
 * Write side.
 *
 * <p>Owns the {@code Transaction} and {@code LedgerEntry} aggregates, the idempotency
 * ledger, optimistic concurrency and the transactional outbox. This is the system of
 * record; it is the only service that writes transaction state.
 */
package dev.ledgerguard.transaction;
