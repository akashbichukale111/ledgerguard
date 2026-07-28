/**
 * Monetary values.
 *
 * <p>{@link dev.ledgerguard.common.core.money.Money} is the only representation of an amount in
 * LedgerGuard. No {@code double}, {@code float}, or bare {@code BigDecimal} is permitted to carry a
 * monetary value — an ArchUnit rule enforces this for domain packages, and a CI grep backs it up.
 *
 * <p>See {@code docs/adr/0009-monetary-representation.md}.
 */
package dev.ledgerguard.common.core.money;
