# Observability

> **Status.** Populated in Phase 9.

## The claim that must be proven, not configured

A single `traceId` must span **gateway → transaction-service → Kafka → reconciliation-service →
Kafka → query-service**: four services and two broker hops.

This is the single most commonly faked feature in systems like this. HTTP propagation works out of
the box; **Kafka propagation does not** — the W3C `traceparent` must be written to and read from
message headers explicitly. Phase 9's acceptance is an **integration test asserting trace
continuity across a broker hop**, not a screenshot.

## Planned contents (Phase 9)

- **Tracing** — Micrometer Tracing + OpenTelemetry bridge → Zipkin. Span attributes include
  `transactionId`, `correlationId`, `sagaId`, `eventType`, so traces are searchable by business
  identity rather than only by URL.
- **Metrics** — RED per service, plus business metrics an operator would actually alert on: match
  rate by rule, auto-match percentage, exception rate by classification, saga
  completion/compensation/timeout counts, **compensation failures**, outbox lag (pending rows and
  oldest row age), consumer lag per group, DLQ depth per topic, projection lag, idempotent-replay
  counts. Every metric is a `MeterRegistry` measurement of a real code path — none estimated.
- **Logging** — structured JSON (Logback + logstash encoder) with a stable field schema:
  `timestamp`, `level`, `service`, `traceId`, `spanId`, `correlationId`, `actor`, `eventType`,
  `aggregateId`, `message`, `errorCode`. MDC populated by HTTP filters and Kafka consumer
  interceptors. `ERROR` means a human must act.

## The demonstrable path

An engineer opens the console, finds a failed transaction, clicks it, sees the lifecycle timeline
with per-hop latency, sees the failing step and its error code, follows the `traceId` into Zipkin,
sees the same flow as spans across four services and two broker hops, then follows the
`correlationId` into the Audit Explorer to see who did what.

Projection lag is surfaced in the UI header so eventual consistency is **visible rather than
hidden**.
