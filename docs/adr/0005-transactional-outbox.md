# 0005. Transactional outbox with a polling publisher, not CDC

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

When `transaction-service` accepts a command it must do two things: persist the aggregate change,
and publish an event so the rest of the system learns about it. These are two different systems —
PostgreSQL and Kafka — and there is no transaction spanning both.

The naive implementation writes to the database, then publishes to Kafka. This is the **dual-write
problem**, and it is broken in both directions:

- Crash after the DB commit, before the publish → the state changed but nobody was told. The
  system is silently inconsistent, and nothing detects it.
- Publish first, then the DB write fails → downstream services react to something that never
  happened.

No amount of retry logic or try/catch fixes this. It is a distributed-systems problem, not a
coding error.

## Decision drivers

- The write path's correctness is the foundation everything else rests on.
- Ordering per aggregate must be preserved (see ADR-0006).
- The mechanism must be demonstrable and testable, including under a simulated crash.
- Operational simplicity at laptop scale.

## Considered options

1. **Dual write with retries** — rejected as incorrect, listed for completeness.
2. **Transactional outbox, polling publisher** (chosen).
3. **Transactional outbox, CDC via Debezium.**
4. **Listen/notify-driven publisher** (`pg_notify` instead of polling).

## Decision outcome

**Chosen: option 2 — transactional outbox with a polling publisher.**

### Mechanism

1. The command handler writes the aggregate change **and** an `outbox` row in **one local ACID
   transaction**. Either both happen or neither does. This is the entire point, and it is why
   PostgreSQL is non-negotiable (ADR-0002).
2. A poller claims a batch:
   ```sql
   SELECT ... FROM outbox
   WHERE published_at IS NULL
   ORDER BY id
   LIMIT :batch
   FOR UPDATE SKIP LOCKED
   ```
   `SKIP LOCKED` is what allows multiple publisher instances to run concurrently without blocking
   each other or double-claiming a row.
3. Each claimed row is published to Kafka **keyed by aggregate ID**, which preserves per-aggregate
   ordering (Kafka guarantees order within a partition, and the key determines the partition).
4. On broker acknowledgement the row is marked published.
5. A retention job prunes published rows on a schedule so the table does not grow without bound.

### The guarantee this actually provides

**At-least-once, not exactly-once.** If the service crashes *after* Kafka acknowledges but
*before* the row is marked published, the row is re-claimed and republished on restart. The event
is delivered twice.

That duplicate is not a defect to be engineered away — it is inherent, and the correct response is
idempotent consumers (ADR-0006). §9 requires a test that simulates exactly this crash window and
asserts the end state is identical to single delivery.

### Consequences

**Positive**

- The dual-write problem is genuinely eliminated, not mitigated.
- Publishing survives Kafka being down: rows accumulate and drain when it returns. The system
  degrades rather than losing data.
- Outbox depth and oldest-row age are directly observable metrics and make excellent alerts —
  they are the earliest signal that publishing has stalled.
- No additional infrastructure.

**Negative**

- **Polling adds latency.** An event is published somewhere between 0 ms and the poll interval
  after commit. This is a real cost of the choice. **The actual measured latency will be recorded
  in `docs/performance.md`; it is deliberately not estimated here** (§0.3 — no invented numbers).
- **Polling adds constant load.** Even an idle system runs the query on every interval. At laptop
  scale this is negligible; at high scale it is a reason to move to CDC.
- The outbox table needs a retention policy, or it becomes the largest table in the database.
- The poller is a component that can silently stop. Its liveness must be monitored — a stalled
  poller looks exactly like an idle system unless outbox lag is being watched.

## Pros and cons of the options

### Option 1 — dual write with retries

- Bad: **incorrect.** Retries reduce the probability of the failure window but cannot close it;
  the process can die between the two operations. Listed only because it is what most systems
  actually do, usually without realising.

### Option 2 — polling publisher (chosen)

- Good: correct, simple, no extra infrastructure, easy to reason about and test.
- Good: the entire mechanism is visible in the repository — a reviewer can read it.
- Bad: latency floor set by the poll interval; constant baseline query load.

### Option 3 — CDC with Debezium

- Good: near-zero publish latency; no polling load; reads the WAL so it cannot miss a committed row.
- Good: **this is the production evolution path** once publish latency or polling load becomes the
  binding constraint.
- Bad: requires Kafka Connect (another container and another thing to operate) plus logical
  replication configuration on PostgreSQL.
- Bad: the mechanism moves into infrastructure configuration, so the repository would demonstrate
  *using* CDC rather than understanding the outbox.
- Bad: against the §0.1 RAM budget.
- **Verdict: correct at scale, wrong at this scale, and the reasoning is the deliverable.**

### Option 4 — `pg_notify`-driven publisher

- Good: much lower latency than polling without new infrastructure.
- Bad: `NOTIFY` is **not durable**. A notification delivered while no listener is connected is lost
  forever. It therefore cannot be the only trigger — a polling fallback is still required for
  correctness, so this is an optimisation on top of option 2 rather than a replacement.
- **Verdict: a legitimate future optimisation; deliberately not implemented now because it adds a
  second code path to the most correctness-critical component in the system.**

## More information

- Why at-least-once is the honest guarantee: `docs/adr/0006-at-least-once-and-idempotency.md`
- Outbox lag runbook: `docs/failure-modes.md`
- Measured publish latency: `docs/performance.md`
