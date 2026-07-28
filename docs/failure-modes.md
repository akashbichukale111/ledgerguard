# Failure modes — operational runbook

> **Status.** Populated in Phase 7 (mechanisms) and Phase 14 (verified procedures). Each entry
> follows: **symptom → diagnosis → resolution**, written for someone paged at 03:00 who did not
> build the system.

A runbook written from imagination is worse than none, because it is trusted during an incident.
Every procedure below is executed and verified before its "verified" marker is added.

## Planned entries

| # | Failure | Symptom |
|---|---|---|
| 1 | Outbox lag | Events stop appearing downstream; `outbox_pending_rows` and oldest-row age climb |
| 2 | Consumer lag | Projections fall behind; the UI's projection-lag indicator rises |
| 3 | DLQ growth | `dlq_depth` rising on one or more topics |
| 4 | Compensation failure | A saga in terminal `COMPENSATION_FAILED`; system could not undo its own partial work |
| 5 | Projection drift | Read model disagrees with the system of record beyond expected lag |
| 6 | Hash-chain break | Audit verification reports a break at index N |
| 7 | Kafka unavailable | Outbox accumulates; writes still succeed; reads serve stale projections |
| 8 | Optimistic-lock retry exhaustion | Writes failing loudly after bounded retries |
| 9 | Saga sweeper not running | Timeouts silently never fire — the most insidious entry here, because the symptom is absence |

## Structure of each entry

- **Symptom** — what an operator sees first, in the UI or an alert
- **Diagnosis** — the specific queries, metrics, and log fields that confirm or rule it out
- **Resolution** — the steps, including which require a role and which are destructive
- **Prevention** — the alert that should have fired earlier

## Degraded-mode behaviour

What the system does when a dependency is down, stated per dependency so operators know what to
expect rather than discovering it live:

| Down | Behaviour |
|---|---|
| Kafka | Writes still succeed; outbox accumulates and drains on recovery. Projections go stale. |
| Redis | **Correctness unaffected**, latency increases. Proven by a test that flushes Redis mid-flow. |
| MongoDB | Read model unavailable; the write path continues. |
| PostgreSQL | Write path unavailable. This is the hard dependency. |
