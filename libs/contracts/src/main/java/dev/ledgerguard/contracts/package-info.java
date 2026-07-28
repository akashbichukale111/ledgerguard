/**
 * Versioned event contracts.
 *
 * <p>Holds the event envelope type and the JSON Schema files that define every message
 * crossing a service boundary. Schemas are stored per event type, one file per version,
 * under {@code src/main/resources/schemas}. There is deliberately no schema-registry
 * container; see {@code docs/adr/0007-schema-in-repo.md}.
 *
 * <p>This module must not depend on any other LedgerGuard module.
 */
package dev.ledgerguard.contracts;
