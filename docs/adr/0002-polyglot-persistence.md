# 0002. Polyglot persistence, and the honest single-store alternative

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

LedgerGuard uses PostgreSQL, MongoDB, and Redis. Three datastores in a system this size invites
the obvious challenge: *is this genuine polyglot persistence, or résumé-driven development?*

This ADR answers that honestly, including the part where one of the three is not strictly
necessary.

## Decision drivers

- Correctness of the write path is non-negotiable and depends on ACID semantics.
- The read model's shape is deeply nested and expected to evolve.
- Every additional datastore is an additional container, an additional backup story, an
  additional failure mode, and an additional thing to explain.
- Redis must never be able to cause an incorrect result, only a slower one.

## Considered options

1. **PostgreSQL only** — JSONB for the read model, `pg_notify`/polling instead of Redis caching.
2. **PostgreSQL + MongoDB + Redis** (chosen).
3. **PostgreSQL + MongoDB + Redis + Elasticsearch** — add a search store for the grid.

## Decision outcome

**Chosen: option 2**, with the following per-store justification and one explicit concession.

### PostgreSQL — non-negotiable

The system of record for the write side. The transactional outbox pattern works *only* because
a single local ACID transaction can span the aggregate mutation and the outbox insert. Remove
that guarantee and the entire reliability story in `docs/reliability.md` collapses — you are
back to dual-write, which is the bug the outbox exists to prevent.

Also hosts:
- the idempotency ledger (correctness depends on a **unique constraint**, not on application logic),
- the saga state and step tables,
- the hash-chained audit log (see ADR-0014 for why this moved out of MongoDB).

### MongoDB — justified, but not required

Holds the read-model projections: Transaction 360 documents and the reconciliation grid.

The genuine fit: a Transaction 360 document is a deeply nested aggregation assembled from many
event types, whose shape evolves as new event types are added. Modelling that relationally means
either a dozen joined tables or a JSONB column — and if it is going to be a JSON blob anyway,
a document store queries it more naturally.

**The honest part, stated as §2.2 requires:** *PostgreSQL JSONB is a completely viable single-store
alternative for this system at this scale.* It would support every query the console needs,
including the keyset-paginated grid and the 360 assembly. Choosing MongoDB costs:

- one more container (~400 MB RAM),
- a second consistency model to reason about and explain,
- a second migration/index-management discipline (`docs/operations.md`),
- a second client library, a second connection pool, a second health check,
- the loss of the ability to join read and write data in one query during debugging.

MongoDB is retained because demonstrating a genuine CQRS read store on separate infrastructure
is one of this system's architectural claims, and because the document shape genuinely fits.
**It is not retained because it is necessary.** A reviewer who says "you could have done this in
Postgres" is correct, and this ADR says so first.

### Redis — never the correctness boundary

Three uses, all of them latency optimisations:

1. Gateway rate-limit buckets.
2. Dashboard aggregate cache, short TTL.
3. Fast-path idempotency-key probe.

**The critical constraint:** the authoritative idempotency record lives in PostgreSQL behind a
unique constraint. Redis is consulted first only to avoid a database round-trip on the common
path. If Redis is empty, stale, or wrong, the Postgres constraint still rejects the duplicate.
Redis being unavailable must cost latency and nothing else.

This is not a claim to be taken on trust — §9 requires a test that **flushes Redis mid-flow** and
asserts that behaviour is unchanged. If that test cannot be made to pass, this decision is wrong
and Redis must be removed from the idempotency path.

### Consequences

**Positive**

- The write path keeps real ACID guarantees where correctness depends on them.
- The read model can be rebuilt from the event log without touching the system of record.
- Redis can be lost entirely without a correctness incident.

**Negative**

- Three datastores to run, monitor, back up, and reason about — the dominant operational cost of
  this architecture.
- Two consistency models. Every read-path bug now has "is this a projection lag or a real
  inconsistency?" as its first diagnostic question. This is why projection lag is surfaced in the
  console header rather than hidden.
- `query-service` touches all three stores, which is more coupling than any other service carries.
- Roughly 900 MB–1.2 GB of the laptop budget is datastore containers.

## Pros and cons of the options

### Option 1 — PostgreSQL only

- Good: one container, one consistency model, one backup story, meaningfully less RAM.
- Good: read and write data can be joined during debugging.
- Good: JSONB with GIN indexes genuinely handles the 360 document and the grid at this scale.
- Bad: cannot demonstrate a physically separate read store, which is a core claim here.
- **This option is not wrong.** For a production system of this size it is arguably the better
  engineering choice, and it is the first thing this project would consolidate to under
  operational pressure.

### Option 2 — Postgres + Mongo + Redis (chosen)

- Good: each store does what it is genuinely best at.
- Good: demonstrates CQRS with a real physical read/write split.
- Bad: three stores of operational surface for a system that could run on one.

### Option 3 — adding Elasticsearch

- Good: richer full-text search over references and counterparty names.
- Bad: rejected outright. The grid's filters are structured (status, amount range, currency, date
  range, assignee) and are served correctly by compound indexes. Elasticsearch would add ~1 GB of
  RAM, a third consistency model, and an index-sync problem, to solve a search problem this system
  does not have. See `docs/architecture.md` for the full rejected-patterns list.

## More information

- Why the audit chain moved to PostgreSQL: `docs/adr/0014-audit-chain-in-postgresql.md`
- The outbox mechanism that depends on ACID: `docs/adr/0005-transactional-outbox.md`
- Idempotency layers: `docs/adr/0006-at-least-once-and-idempotency.md`
