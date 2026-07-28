# Architecture Decision Records

Format: [MADR](https://adr.github.io/madr/). Each record states context, the decision, the
alternatives that were genuinely considered, and the consequences — **including the negative
ones**. An ADR listing only benefits is marketing, not a decision record.

ADRs are immutable once accepted. A changed decision means a new ADR that supersedes the old one;
the original stays in place so the reasoning history survives.

| # | Decision | Status |
|---|---|---|
| [0001](0001-service-decomposition.md) | Four services, split by scaling / availability / security boundary | Accepted |
| [0002](0002-polyglot-persistence.md) | PostgreSQL + MongoDB + Redis — and why Postgres-only is a viable alternative | Accepted |
| [0003](0003-event-sourcing-scope.md) | Event-source `ReconciliationCase` only; state-store everything else | Accepted |
| [0004](0004-orchestrated-saga-hand-rolled.md) | Orchestrated saga, hand-rolled; workflow engines rejected | Accepted |
| [0005](0005-transactional-outbox.md) | Transactional outbox with a polling publisher, not CDC | Accepted |
| [0006](0006-at-least-once-and-idempotency.md) | At-least-once delivery + idempotent consumers, never "exactly-once" | Accepted |
| [0007](0007-schema-in-repo.md) | Versioned JSON Schema in the repo, not a schema registry | Accepted |
| [0008](0008-keyset-pagination.md) | Keyset (cursor) pagination, not offset | Accepted |
| [0009](0009-monetary-representation.md) | `BigDecimal` + currency, scale from ISO 4217 minor units | Accepted |
| [0010](0010-uuidv7-identifiers.md) | UUIDv7 identifiers | Accepted |
| [0011](0011-tamper-evident-audit-chain.md) | Hash-chained audit log — tamper-**evident**, not tamper-proof | Accepted |
| [0012](0012-non-blocking-retry-topics.md) | Non-blocking retry topics + error classification | Accepted |
| [0013](0013-hexagonal-layering.md) | Hexagonal-ish layering, enforced by ArchUnit | Accepted |
| [0014](0014-audit-chain-in-postgresql.md) | Audit chain in PostgreSQL — a deliberate deviation from the build prompt | Accepted |

## The decisions that carry the most risk

If you have ten minutes, read these four. They are where this system is most likely to be wrong,
and where the reasoning matters most:

- **[0002](0002-polyglot-persistence.md)** — three datastores in a system that could run on one.
  The ADR concedes this before a reviewer has to raise it.
- **[0006](0006-at-least-once-and-idempotency.md)** — the guarantee this system actually provides.
  The phrase "exactly-once delivery" appears nowhere in this repository except in explanations of
  why it is unachievable.
- **[0011](0011-tamper-evident-audit-chain.md)** — tamper-evident, not tamper-proof. An attacker
  with full table write access can rebuild the chain.
- **[0014](0014-audit-chain-in-postgresql.md)** — a documented deviation from the specification
  this system was built against, with the cost stated.
