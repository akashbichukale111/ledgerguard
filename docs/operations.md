# Operations

> **Status.** Populated in Phases 2, 7, and 13.

## Planned contents

- **Running the stack** — compose profiles (`infra`, `core`, `full`), what each costs in RAM, and
  which to use when
- **Migrations** — Flyway for PostgreSQL (versioned, **immutable once committed**, `V…`/`R…`
  conventions, with a test that runs all migrations from scratch); a versioned index/collection
  initialiser for MongoDB under the same discipline
- **Health and readiness** — Actuator readiness/liveness groups. **Readiness genuinely depends on
  Kafka consumer assignment and database connectivity**; a probe that returns `UP` unconditionally
  is worse than no probe, because it defeats orchestration
- **Graceful shutdown** — draining in-flight work before closing consumers
- **DLQ replay** — the `OPERATIONS`-role procedure, and why it is safe (idempotent consumers)
- **Backup and retention** — outbox pruning, `processed_event` retention tied to the maximum
  plausible redelivery window, audit log retention (append-only: retention means archival, never
  deletion)
- **Rebuilding a projection** — replay from the topic; the read model is disposable by design
- **Verifying the audit chain** — endpoint and CLI

## Kafka posture

Local: **single broker, KRaft, no ZooKeeper**. Replication factor 1.

Production would require replication factor ≥ 3 with `min.insync.replicas=2` and `acks=all`, so
that a single broker loss neither loses data nor blocks writes. **None of that is tested here** and
this document does not imply otherwise.
