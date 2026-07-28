/**
 * Observability conventions.
 *
 * <p>Trace context propagation (including across Kafka headers), MDC population, the
 * structured-logging field schema and metric naming. Conventions live here so that four
 * services cannot drift into four different log shapes.
 */
package dev.ledgerguard.common.observability;
