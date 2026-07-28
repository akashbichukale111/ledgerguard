/**
 * Identifier generation.
 *
 * <p>All aggregate and event identifiers are UUIDv7: time-ordered, so they keep B-tree index
 * locality while still being generated without a database round trip. See
 * {@code docs/adr/0010-uuidv7-identifiers.md}.
 */
package dev.ledgerguard.common.core.id;
