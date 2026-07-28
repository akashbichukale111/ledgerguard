# Reliability guarantees

> **Status.** The guarantees below are decided and binding as of Phase 1. They are *implemented*
> across Phases 4–7 and each is backed by a named test; the evidence lands in those phase reports.
> Until then, treat this as a specification, not a claim about running code.

## What LedgerGuard guarantees

| Property | Guarantee |
|---|---|
| Delivery | **At-least-once.** Duplicates are possible and expected. |
| Processing | **Effectively-once**, because consumers are idempotent. |
| Ordering | **Per Kafka partition only**, therefore per aggregate key only. |
| Durability of accepted writes | Committed to PostgreSQL before the response returns. |
| Read model | **Eventually consistent.** Lag is measured and surfaced in the UI. |
| Audit log | **Tamper-evident**, not tamper-proof. |

## What LedgerGuard does NOT guarantee

Stated first, because these are the claims that get overstated:

- **No exactly-once delivery.** It is not achievable across PostgreSQL, Kafka, and a consumer's own
  datastore. See [ADR-0006](adr/0006-at-least-once-and-idempotency.md).
- **No global ordering.** Events for different aggregates may interleave arbitrarily. Logic that
  depends on a global order is a design error in this system.
- **No tamper-proof audit.** An attacker with write access to the entire audit table can rebuild
  the chain and pass verification. See [ADR-0011](adr/0011-tamper-evident-audit-chain.md).
- **No read-your-writes on the query API.** A write that returns `202` is not immediately visible
  in the read model.
- **No guarantee that a timeout fires if the saga sweeper is not running.**

## The three idempotency layers

1. **API** — `Idempotency-Key` header, unique constraint, body-hash comparison.
2. **Consumer** — `ProcessedEventRecord` on `(consumerGroup, eventId)`, written **in the same
   transaction** as the projection. Never Redis, never in-memory.
3. **Aggregate** — optimistic concurrency with bounded, jittered retry.

Detail in [ADR-0006](adr/0006-at-least-once-and-idempotency.md).

## Tests that must pass for these claims to stand

Listed here so the claims can be checked against evidence rather than trusted:

- Duplicate HTTP request, same key → one aggregate, one outbox row, one event, identical response
- Same Kafka event delivered three times → projection identical to single delivery
- Publisher crash between Kafka ack and outbox mark → no loss, duplicate handled idempotently
- Saga step failure → compensation executes → `COMPENSATED`
- Compensation failure → `COMPENSATION_FAILED` surfaces, is not swallowed
- Saga timeout via fixed-clock advancement
- Poison message → classified non-retryable → DLT **without** exhausting the retry ladder
- DLQ replay → idempotent, no duplicate projection rows, audit event written
- Redis flushed mid-flow → correctness unaffected
- Optimistic-lock collision under concurrent writers → one succeeds, one retries, no lost update
- Audit record tampered directly in the database → verification detects it at the correct index

## Related

- [`failure-modes.md`](failure-modes.md) — what to do when these degrade
- [`testing.md`](testing.md) — how the above is verified
