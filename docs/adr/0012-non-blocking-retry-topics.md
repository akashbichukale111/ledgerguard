# 0012. Non-blocking retry topics with error classification, not in-place retry

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

A consumer fails to process a message. Sometimes the cause is transient (the database was briefly
unavailable) and retrying will succeed. Sometimes it is permanent (the payload is malformed) and
retrying will fail identically, forever.

Treating these the same is the defining mistake in most Kafka consumer implementations.

## Decision drivers

- One bad message must not stall unrelated work.
- A permanent failure must not consume the retry budget.
- Failed messages must be inspectable and replayable, not lost.
- Ordering per key must be preserved on the happy path.

## Considered options

1. **Blocking retry in place** — catch, sleep, retry within the poll loop.
2. **Non-blocking retry topics with a timed ladder** (chosen).
3. **Immediate DLQ on any failure** — no retry at all.

## Decision outcome

**Chosen: option 2**, combined with **mandatory error classification**.

### Why blocking retry is wrong

Kafka delivers messages from a partition **in order, one batch at a time**. A consumer that sleeps
and retries in place holds the partition. Everything behind that message waits.

Concretely: transaction `A` hits a transient database error and retries with backoff for 60
seconds. Transactions `B` through `Z` are on the same partition. **None of them are processed for
60 seconds**, despite having nothing to do with `A` and no failure of their own. One unlucky
message has stalled unrelated business flows — a head-of-line blocking failure.

Worse, if the retry exceeds `max.poll.interval.ms` the broker considers the consumer dead and
triggers a rebalance, which stalls the *entire consumer group*, not just one partition.

### The mechanism

On failure, the message is **republished to a timed retry topic** and the original is
acknowledged. The partition moves on immediately.

```
<topic>            → <topic>.retry.1  (5s)
                   → <topic>.retry.2  (30s)
                   → <topic>.retry.3  (5m)
                   → <topic>.dlt
```

Delays carry **jitter** so that a dependency recovering from an outage does not receive every
retry simultaneously — a synchronised retry storm can re-break the thing that just recovered.

### Error classification — the part that separates real work from a tutorial

Before retrying, the failure is classified:

**Non-retryable → straight to DLT, no retry ladder.**
- Deserialization failure
- Schema violation
- Validation error
- Business-rule rejection

These are deterministic. The message will fail identically in 5 seconds, 30 seconds, and 5
minutes. Sending it through the ladder wastes three delays, three topic writes, and — critically —
**delays the operator's discovery of the problem by five and a half minutes** while producing
identical failures in the logs.

**Retryable → the ladder.**
- Transient database errors, connection failures, timeouts, lock contention

**Unknown → treated as retryable**, but this is a deliberate default worth stating: an
unclassified error is a gap in the classifier, so the safe behaviour is to retry (risking wasted
work) rather than to DLT (risking discarding a recoverable message). New exception types should be
classified explicitly rather than relying on the default.

### DLT payload

A dead-lettered message carries the full original envelope plus `failureReason`, `exceptionClass`,
`stackTraceDigest`, `attemptCount`, `originalTopic`/`partition`/`offset`, `firstFailedAt`, and
`lastFailedAt`. A DLQ entry that only holds the payload is nearly useless during an incident —
the operator needs to know what failed and where it came from.

### Replay

An authenticated endpoint (role `OPERATIONS`) republishes selected DLT messages to the source
topic with a **new `causationId`**, writes an audit event, and is itself idempotent.

**Replay is safe by construction because consumers are idempotent** (ADR-0006) — which is why that
property is worth its cost. §9 requires the test that proves it.

### Consequences

**Positive**

- One poison message cannot stall a partition.
- Permanent failures reach the DLQ in seconds, so operators see them immediately.
- Retry state is visible as topic contents rather than hidden in consumer memory.
- Retry budget is spent only where retrying can help.

**Negative**

- **Ordering is lost for retried messages.** A message that goes to `retry.1` is reprocessed after
  later messages for the same key have already been handled. This is a real semantic weakening and
  the main cost of this decision. It is acceptable **because consumers are idempotent and
  state transitions are guarded by the state machine** — a stale event arriving late is rejected as
  an illegal transition rather than corrupting state. A system requiring strict per-key ordering
  even under failure could not use this pattern.
- More topics to create, monitor, and reason about (4 per source topic).
- A message can be in flight for over five minutes before reaching the DLQ, so end-to-end latency
  under failure is much worse than the happy path — monitoring must account for it.
- The classifier is a correctness-relevant component: misclassifying a transient error as
  non-retryable discards recoverable work. It is unit-tested per exception type.

## Pros and cons of the options

### Option 1 — blocking retry

- Good: preserves strict ordering; trivially simple; no extra topics.
- Bad: head-of-line blocking; rebalance risk; retry state lost on restart.

### Option 2 — non-blocking retry topics (chosen)

- Good: no head-of-line blocking; durable, inspectable retry state.
- Bad: ordering weakened for retried messages; more topics.

### Option 3 — immediate DLQ, no retry

- Good: simplest possible; nothing stalls; failures are immediately visible.
- Bad: a two-second database blip dead-letters everything in flight, converting a self-healing
  transient failure into a manual replay operation. The operational burden is unacceptable.

## More information

- Idempotency that makes replay safe: `docs/adr/0006-at-least-once-and-idempotency.md`
- Topology and topic naming: `docs/architecture.md`
- DLQ growth runbook: `docs/failure-modes.md`
