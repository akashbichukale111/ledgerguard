# Demo scenarios

> **Status.** Populated in Phase 14, alongside `scripts/demo/`.

Every script is **numbered, idempotent, and safe to re-run**, and prints at minimum the correlation
ID and the direct console URL for the entity it created. `make demo` runs the full sequence with
narration to stdout.

| # | Scenario | What to watch |
|---|---|---|
| 1 | Happy path | Live feed, Transaction 360 timeline, the trace in Zipkin |
| 2 | Successful auto-reconciliation | The case auto-matches; the explanation names the exact rule |
| 3 | Idempotent duplicate | One aggregate, one event, `Idempotent-Replay: true`, metric increments |
| 4 | Amount mismatch → exception | Break with full explanation; resolve with justification; audit entries |
| 5 | Duplicate detection | Same payment presented twice is flagged, **not** matched |
| 6 | Saga step failure → compensation | `COMPENSATING` → `COMPENSATED`, UI updating live |
| 7 | Poison message → DLQ | Non-retryable classification, immediate DLT, DLQ Operations screen |
| 8 | DLQ replay | Idempotent reprocessing, no duplicated projection state |
| 9 | Audit integrity | Verification passes; tamper via direct DB write; re-verify fails **at the correct index** |
| 10 | Load burst | k6 spike; consumer and outbox lag rise and drain on the Overview |

Scenario 9 is a demonstration of **tamper-evidence**. It shows detection, not prevention — see
[ADR-0011](adr/0011-tamper-evident-audit-chain.md).
