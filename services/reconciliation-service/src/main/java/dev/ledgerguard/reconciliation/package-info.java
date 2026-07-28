/**
 * Reconciliation and orchestration.
 *
 * <p>Hosts the hand-rolled saga orchestrator, the deterministic matching engine and the
 * exception lifecycle. The {@code ReconciliationCase} aggregate is event-sourced; nothing
 * else in the system is. See {@code docs/adr/0003-event-sourcing-scope.md}.
 */
package dev.ledgerguard.reconciliation;
