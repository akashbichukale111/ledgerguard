# 0014. The audit chain lives in PostgreSQL, not MongoDB

- **Status:** Accepted
- **Date:** 2026-07-28
- **Note:** This ADR records a **deliberate deviation from the master build prompt** (§2.1), first
  stated in `docs/phase-reports/phase-00.md` §6.1.

## Context and problem statement

The master build prompt specifies two things that turn out to be in tension:

- **§2.1** places the audit archive in `query-service` backed by **MongoDB**, alongside the read
  projections.
- **§6** requires that the audit table have **no `UPDATE`/`DELETE` grants for the application
  role, enforced by a database grant in migration**, and that each record store a `previousHash`
  forming a verifiable chain.

These cannot both be satisfied well in MongoDB. This ADR resolves the conflict and explains the
cost of the resolution.

## Why the conflict is real

A hash chain has one hard requirement: **every append must observe the hash of the immediately
preceding record.** That implies a single, serialised writer with a total order. Two concurrent
appends that both read the same `previousHash` produce a fork, and the chain is silently broken
from that point.

**What PostgreSQL provides directly:**
- A `BIGSERIAL`/sequence giving a total order.
- A `UNIQUE` constraint on the chain index, so a fork fails loudly at the constraint rather than
  producing two valid-looking records.
- `SERIALIZABLE` isolation, or a simple `SELECT ... FOR UPDATE` on the tail, to serialise appends.
- **`REVOKE UPDATE, DELETE ON audit_event FROM app_role` — a real, enforceable grant**, executed in
  a Flyway migration. This is the §6 requirement, literally.

**What MongoDB would require us to build:**
- A unique index on the chain index — available, but the fork must still be prevented, needing a
  transaction (MongoDB supports them, but they are heavier and were not the reason we chose Mongo).
- Serialisation of appends — our responsibility, in application code.
- Grant enforcement — a role restricted to `find` and `insert` on the collection is *possible*, but
  MongoDB's built-in roles are coarser, custom roles are more work to express and verify, and the
  guarantee is weaker than a SQL `REVOKE` a reviewer can read in a migration file.

Choosing MongoDB means hand-building, in application code, the exact properties PostgreSQL already
guarantees — for the one component in the system whose entire value is being trustworthy.

## Considered options

1. **Audit chain in MongoDB**, as the prompt's §2.1 states.
2. **Audit chain in PostgreSQL**, owned by `query-service` (chosen).
3. **Audit chain in PostgreSQL**, owned by a fifth "audit-service".
4. **Drop the chain**, keep a plain append-only Mongo collection.

## Decision outcome

**Chosen: option 2.** The authoritative hash-chained audit log lives in a PostgreSQL schema owned
by `query-service`. MongoDB continues to hold the read projections (Transaction 360, the grid).

`query-service` therefore touches **three datastores**: MongoDB (projections), PostgreSQL (audit
chain), Redis (cache).

### Consequences

**Positive**

- Chain integrity is guaranteed by database constraints rather than by application discipline.
- The §6 grant requirement is satisfied literally and verifiably — a reviewer reads one migration.
- Verification is a straightforward ordered walk of an indexed table.
- The tamper test required by §9 has a clean implementation: `UPDATE` the row as a superuser and
  assert verification fails at the right index.

**Negative — stated plainly**

- **`query-service` now has three datastores.** That is more coupling than any other service
  carries and is the direct cost of this decision. It complicates the service's startup, health
  check, connection management, and failure analysis.
- **It contradicts the clean "write side = Postgres, read side = Mongo" story** that made ADR-0002
  easy to explain. The narrative is now "read side = Mongo, except audit, which is Postgres,
  because chains need serialised appends." That is a worse sentence, and it is the honest one.
- Two transaction managers in one service, which is a known source of subtle bugs. Mitigated by
  the audit path and the projection path being entirely separate flows that never participate in a
  shared transaction.
- Serialised appends are a **throughput ceiling** on the audit path (ADR-0011). PostgreSQL makes
  the ceiling explicit and enforced rather than probabilistic.

## Pros and cons of the options

### Option 1 — MongoDB (as specified in the prompt)

- Good: keeps `query-service` to two datastores; matches the prompt as written; audit records are
  schema-varied, which documents suit.
- Bad: chain serialisation becomes application code — the property most needing to be
  bulletproof would be the least protected.
- Bad: grant enforcement is weaker and harder to demonstrate to a reviewer.
- **Verdict: workable, but it makes the audit log's core guarantee depend on our code rather than
  the database's. For the one component whose purpose is trustworthiness, that is the wrong
  trade.**

### Option 2 — PostgreSQL in `query-service` (chosen)

- Good: constraints and grants do the work; requirement satisfied literally.
- Bad: a third datastore in one service.

### Option 3 — a dedicated `audit-service`

- Good: cleanest separation; audit gets its own credentials, its own database, its own deployment
  and blast radius. **This is what a production system should do** — the audit log arguably
  warrants a separate trust boundary, since the point is that the services being audited should not
  be able to rewrite their own history.
- Bad: a fifth JVM against the §0.1 RAM budget (~400–600 MB).
- Bad: audit writes become a network call, so an audit failure has to be handled by every caller —
  and "what happens when the audit write fails?" becomes a much harder question than it is in-process.
- **Verdict: architecturally superior, rejected on the resource budget. This is the first thing to
  extract if the constraint is lifted, and it is recorded as future work in the README.**

### Option 4 — drop the chain

- Good: simplest; MongoDB is a fine append-only store.
- Bad: gives up tamper-evidence entirely, which is a headline capability. Rejected.

## More information

- Chain mechanism and the tamper-evident vs tamper-proof distinction: `docs/adr/0011-tamper-evident-audit-chain.md`
- Store-by-store justification: `docs/adr/0002-polyglot-persistence.md`
- Original deviation record: `docs/phase-reports/phase-00.md` §6.1
