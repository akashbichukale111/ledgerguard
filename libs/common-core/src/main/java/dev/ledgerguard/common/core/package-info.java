/**
 * Framework-free domain primitives.
 *
 * <p>Contains {@code Money}, the identifier types, the clock abstraction, the typed error
 * hierarchy and RFC 9457 Problem Details. Nothing here may reference Spring, JPA or Kafka:
 * an ArchUnit rule enforces that, so the primitives stay testable in isolation and usable
 * from any layer.
 */
package dev.ledgerguard.common.core;
