# 0006. At-least-once delivery with idempotent consumers, not "exactly-once"

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

"Exactly-once delivery" is the most common false claim in event-driven architecture write-ups. It
is not achievable across heterogeneous systems, and claiming it is the fastest way to fail a
review by someone who knows the field.

This ADR states what LedgerGuard actually guarantees, and why the stronger claim is impossible.

## Why exactly-once delivery is impossible here

Delivery involves at least three independent systems that can each fail between any two steps:
PostgreSQL, Kafka, and the consumer's own datastore. Any acknowledgement protocol between two
parties over an unreliable channel has a window where the sender does not know whether the
receiver committed — the Two Generals problem. The sender must then choose:

- **Retry** → possible duplicate (at-least-once).
- **Do not retry** → possible loss (at-most-once).

There is no third option. Kafka's `enable.idempotence` and transactional producers provide
exactly-once semantics **within Kafka** — producer-to-broker deduplication and atomic
consume-transform-produce across Kafka topics. They say nothing about a consumer writing to
MongoDB or PostgreSQL, which is where our side effects land.

## Decision drivers

- The claim made in documentation must match the guarantee the code provides.
- Duplicates must be harmless, since they are unavoidable.
- The mechanism must not depend on Redis or in-memory state (which lose data on restart).

## Considered options

1. **Claim exactly-once, rely on Kafka transactions** — rejected as dishonest and incorrect.
2. **At-most-once** — acknowledge before processing.
3. **At-least-once + idempotent consumers** (chosen).

## Decision outcome

**Chosen: option 3.** LedgerGuard guarantees:

> **At-least-once delivery. Consumers are idempotent. The composition yields
> effectively-once *processing*.**

"Effectively-once processing" is a statement about *observable outcomes* — applying the same event
twice leaves the same state as applying it once. It is not a claim that the message arrives once.
The distinction is the whole point, and the phrase "exactly-once delivery" is banned from this
repository outside documentation explaining why (§0.2).

### Three independent idempotency layers

**1. API level.** `Idempotency-Key` header required on POST/PUT commands. Stored as
`(key, endpoint, request_body_hash, response_status, response_body, created_at)` with a **unique
constraint** on the key.
- Same key + same body → the original response, HTTP 200, header `Idempotent-Replay: true`.
- Same key + **different** body → RFC 9457 Problem, `409 Conflict`. Silently returning the first
  response would be worse than failing: the caller believes a different request succeeded.
- In-flight duplicate → `409` with `Retry-After`.

**2. Consumer level.** `ProcessedEventRecord` keyed on `(consumerGroup, eventId)` with a unique
constraint, written **in the same transaction as the projection or state change**. This is the
part that is usually got wrong: if the dedupe record is written in a separate transaction, a crash
between the two re-opens exactly the window it was meant to close.
- Duplicate delivery → detected, skipped, counted in a metric, logged at debug.
- **Never Redis, never an in-memory cache.** Both lose their contents on restart, which is
  precisely when redelivery happens.

**3. Aggregate level.** Optimistic concurrency via `@Version`. On
`OptimisticLockingFailureException`: retry with exponential backoff **plus full jitter**, bounded
attempts, and a metric. Beyond the bound, fail loudly rather than silently dropping the write.

### Ordering — stated precisely

**Ordering is guaranteed per Kafka partition only, therefore per aggregate key only.** Events are
published keyed by aggregate ID, so all events for one transaction land on one partition and are
consumed in order.

**Global ordering across aggregates does not exist** and must never be implied. Two transactions
processed concurrently may have their events interleaved arbitrarily. Any logic requiring a global
order is a design error in this system.

### Consequences

**Positive**

- The documented guarantee matches the implemented behaviour, which is the point.
- Duplicates are boring: replay tooling, consumer restarts, and rebalances are all safe by
  construction.
- DLQ replay (§5.2) is safe *because* consumers are idempotent — the property pays for itself
  twice.

**Negative**

- Every consumer must be written idempotently, and a new consumer that forgets is a latent bug
  that only appears under redelivery. Mitigated by making the dedupe check part of the shared
  consumer support in `common-kafka` rather than something each consumer implements.
- The `processed_event` table grows and needs a retention policy tied to the maximum plausible
  redelivery window.
- One extra write per consumed event.
- Callers must generate and manage idempotency keys — a real API-ergonomics cost, documented for
  clients.

## Pros and cons of the options

### Option 1 — claim exactly-once

- Bad: false. Kafka's guarantees stop at Kafka's boundary; our side effects are outside it.
- Bad: the claim itself is a credibility failure. A reviewer who knows this field stops reading.

### Option 2 — at-most-once

- Good: no duplicates; simpler consumers.
- Bad: **loses events** on consumer crash between acknowledgement and processing. For a financial
  reconciliation system, a silently dropped transaction event is the worst possible failure — far
  worse than processing one twice when processing is idempotent.

### Option 3 — at-least-once + idempotency (chosen)

- Good: no loss; duplicates rendered harmless; honest.
- Bad: idempotency discipline required everywhere, forever.

## More information

- The tests that prove this: `docs/testing.md` (duplicate HTTP request, triple event delivery,
  crash between publish and mark, Redis flush mid-flow)
- Retry and DLQ topology: `docs/adr/0012-non-blocking-retry-topics.md`
- Reliability guarantees as stated to users: `docs/reliability.md`
