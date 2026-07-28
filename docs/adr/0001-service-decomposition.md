# 0001. Service decomposition boundaries

- **Status:** Accepted
- **Date:** 2026-07-28
- **Supersedes:** —
- **Superseded by:** —

## Context and problem statement

LedgerGuard has to ingest transactions, orchestrate a reconciliation workflow, match entries,
and serve an operations console. That work could be split anywhere on a spectrum from a single
deployable to a dozen microservices.

The failure mode on portfolio projects is well known: split into twelve services because
"microservices" is the expected answer, then discover that eleven of them share a database, all
of them deploy together, and none of them can be reasoned about independently. That is a
distributed monolith with extra latency, and a reviewer spots it in minutes.

The question is therefore not "how many services" but **which boundaries are real** — where does
a genuine difference in scaling, availability, or security posture justify the cost of a network
hop, a serialization boundary, and an independent failure domain?

## Decision drivers

- Every boundary must be justified by a difference in **scaling, availability, or security**, not
  by taxonomy.
- The whole stack must boot on a developer laptop inside the §0.1 budget (< 10 GB RAM). Each
  additional JVM costs roughly 300–600 MB.
- The write path and the read path have genuinely different characteristics and should be able
  to fail and scale separately.
- A reviewer must be able to hold the topology in their head.

## Considered options

1. **Single modular monolith** — one deployable, modules enforced by ArchUnit.
2. **Four services split by read/write and workflow ownership** (chosen).
3. **Service per aggregate** — transaction, ledger-entry, case, exception, audit, saga, … (8–12).

## Decision outcome

**Chosen: option 2 — four JVM services plus a local authorization server.**

| Service | Why it is a separate deployable |
|---|---|
| `ledgerguard-gateway` | Different **security** posture. It is the only internet-facing process; it terminates auth, rate-limits, and sets security headers. Compromise here must not imply access to a datastore, so it holds no DB credentials and no business logic. |
| `transaction-service` | Different **consistency** requirement. It is the system of record and the only writer of transaction state. Its correctness depends on a local ACID transaction spanning aggregate + outbox, which is exactly what must not be shared with anything else. |
| `reconciliation-service` | Different **scaling** profile. Matching is CPU-bound and bursty (a counterparty file lands and creates a spike); ingestion is IO-bound and steady. Coupling them means provisioning for the sum of two unrelated peaks. |
| `query-service` | Different **availability** requirement. The console must stay readable while the write path is degraded — an operator's first instinct during an incident is to open the dashboard. Serving reads from a separately-deployed, separately-scaled projection store is the point of the split. |
| `auth-server` | Different **trust** boundary and **lifecycle**. It holds signing keys and changes far less often than application code. |

Shared code lives in Maven modules (`libs/`), which are libraries, not services: no module in
`libs/` opens a port or owns a datastore.

### Consequences

**Positive**

- Each boundary survives the question "what breaks if you merge this with its neighbour?"
- The read path can be scaled or restarted without touching the write path.
- The gateway holds no datastore credentials, so an edge compromise does not directly yield data.

**Negative — stated plainly**

- Four JVMs cost roughly 1.5–2.5 GB of the laptop budget before any infrastructure container.
  This is the single largest line item in the §0.1 budget.
- Cross-service changes (a new event field consumed by the projection) now require a coordinated
  change across two modules and a schema version, instead of one commit.
- Debugging requires distributed tracing to be genuinely working — which is why §7 treats trace
  propagation across Kafka as a tested requirement rather than a configuration checkbox.
- Every service pays the Spring Boot startup cost, which dominates the cold-start budget.

**Neutral**

- Option 1 (modular monolith) is a legitimate production choice for this problem at this scale
  and would be **cheaper to operate**. It is rejected here because the read/write and
  ingest/match split are the architectural claims this system exists to demonstrate, and a
  monolith cannot demonstrate them. That is an honest statement about a portfolio system's
  purpose, not a claim that four services are objectively correct for this workload.

## Pros and cons of the options

### Option 1 — modular monolith

- Good: lowest RAM, simplest deployment, no distributed-systems failure modes, no eventual
  consistency to explain.
- Good: refactoring across module boundaries is a compiler problem, not a migration.
- Bad: cannot demonstrate CQRS across a real network boundary, saga across services, or trace
  propagation across a broker — the things this repository is built to show.
- Bad: one bad deployment takes down ingestion and the console together.

### Option 2 — four services (chosen)

- Good: each boundary is defensible under scrutiny.
- Good: fits the resource budget.
- Bad: four times the operational surface; eventual consistency becomes visible to users.

### Option 3 — service per aggregate

- Good: maximum theoretical independence.
- Bad: blows the RAM budget outright (8–12 JVMs).
- Bad: aggregates that change together (transaction and ledger entry) would sit behind a network
  call from each other, forcing either a distributed transaction or a saga for what is genuinely
  one local invariant. This is the classic over-decomposition error.
- Bad: nobody can hold it in their head, which defeats the purpose of a reference implementation.

## More information

- Rejected patterns and the reasoning behind each: `docs/architecture.md`
- The read/write consistency boundary: `docs/adr/0003-event-sourcing-scope.md`
- Persistence choices per service: `docs/adr/0002-polyglot-persistence.md`
