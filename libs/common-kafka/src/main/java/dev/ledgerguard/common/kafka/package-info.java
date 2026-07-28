/**
 * Shared Kafka mechanics.
 *
 * <p>Envelope serialization, the idempotent-consumer support that backs
 * {@code ProcessedEventRecord}, and the non-blocking retry and dead-letter topology that
 * every consumer inherits. See {@code docs/adr/0012-non-blocking-retry-topics.md}.
 */
package dev.ledgerguard.common.kafka;
