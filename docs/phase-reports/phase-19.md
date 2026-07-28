# Phase 19: A Contracted Reconciliation Event, and a Real Gateway

**Status**: ✅ Build green — 320 unit tests, 0 failures. Two long-standing empty spots filled.
One regression introduced during this phase, caught by CI and fixed — see §3a.

Everything in §1–§3 was produced by running the command shown. §5 states what is still unexecuted
or absent.

---

## 1. The reconciliation event

### What existed before

`TransactionReceived` was the only contracted event type. The projection therefore only ever wrote
status `RECEIVED`, no transaction ever reached a terminal state, and match rate and error rate had
an empty denominator — which is why Phase 17 omitted them from the dashboard rather than divide by
nothing.

### What was added

**`TransactionReconciled` v1** — `libs/contracts/src/main/resources/schemas/TransactionReconciled/v1.json`,
documented in `docs/event-catalog.md` (the catalog is verified by `EventCatalogCompletenessTest`,
so an undocumented event type fails the build).

The payload carries the outcome **and the reasoning**: `ruleId`, `ruleSetVersion` and
`candidatePoolSize` travel with every decision. A consumer that can see `UNMATCHED` but not which
rule fired against how large a candidate pool cannot act on it, and neither can an auditor reading
it back later.

`outcome` is deliberately narrower than `classification` — three values against the engine's nine —
so a dashboard consumer does not need to know every engine case to answer "did this settle".
`ReconciliationOutcome.of` derives it from `MatchClassification.isAutoResolvable()` rather than a
hand-written case list, so a classification added to the engine cannot silently produce an outcome
nobody chose. A fuzzy match maps to `REQUIRES_REVIEW`, never `MATCHED`, however confident it looks.

### The bigger gap this uncovered

Writing the publisher exposed something the earlier reports had not recorded:
**reconciliation-service had no inbound adapter at all.** No Kafka consumer, nothing that ran the
matching engine, nothing that started a saga. The engine and the saga orchestrator were real,
well-tested code that nothing invoked at runtime.

Shipping a publisher that nothing calls would have repeated exactly the pattern Phase 16 criticised
— a library wired to nothing — so the inbound half was built too:

- `TransactionEventConsumer` — consumes `transactions.events.v1`, dedupes on `processed_event`
  (ADR-0006), publishes the outcome.
- `ReconcileTransactionHandler` — the decision, separated from delivery so it is testable without a
  broker.

**A bug I introduced and then fixed while writing it:** the first version acknowledged the record in
a `finally` block *and* rethrew on failure. That advances the offset past a record that was never
processed, so the container's error handler never sees it and the failure is silent. Acknowledgement
now happens only on paths that completed. `aFailedPublishIsNotAcknowledgedSoTheRecordIsRedelivered`
pins it.

### Metrics restored

`DashboardMetricsService` now publishes `matchRate`, `reviewRate` and `errorRate`, plus
`reconciledCount` and `pendingCount`. Three decisions worth stating:

- **Rates divide by the reconciled population, not by every transaction.** A denominator that
  silently includes in-flight work makes a backlog read as a failure rate. `pendingCount` is
  published alongside so the two stay distinguishable.
- **A rate is `null`, not `0.0`, when nothing has reconciled.** Zero renders as "0% matched", which
  tells an operator the system is failing when the truth is that nothing has finished. The console
  renders null as `—` with the caption "nothing reconciled yet".
- **The rates are exact counts over the whole collection**, unlike the lag and last-hour figures
  which are sampled at 200 documents. A match rate computed off a recent window would swing with
  arrival order and mean nothing. The console says which is which.

**Consumer lag is still absent.** It needs a Kafka `AdminClient` querying group offsets, which this
service does not have. Unchanged from Phase 17.

---

## 2. The gateway

`services/ledgerguard-gateway` contained one `package-info.java`. It is now a running Spring Cloud
Gateway application.

**Routing** (`RoutingConfig`) — declared in Java rather than YAML because the ordering is
load-bearing and needed to be testable. Gateway takes the first matching route, and both the auth
path and the transaction write path are subsets of the `/api/v1/**` catch-all; declaring the
catch-all first would silently send every login to the query service, which does not implement it.

| Order | Route | Target |
|---|---|---|
| 1 | `/api/v1/auth/**` | auth-server |
| 2 | `POST /api/v1/transactions` | transaction-service |
| 3 | `/api/v1/**` | query-service |

Rules 2 and 3 are the CQRS boundary made visible at the edge — the same path, routed by method. A
`POST` is an instruction; a `GET` is a projection read.

**Correlation-ID minting** (`CorrelationIdFilter`) — the edge is the only place that can guarantee
every request has one. A service minting its own would produce a different ID per hop, which is
precisely the failure a correlation ID exists to prevent. A caller-supplied ID is honoured, but
parsed as a UUID first: an unvalidated header lets a caller inject arbitrary text into every
downstream log line. Casing is normalised so one flow does not appear twice in a log search.

**Security headers** (`SecurityHeadersFilter`) — set at the edge so a new service cannot ship
without them.

**Not implemented, though the module description named them:** JWT validation and rate limiting.
JWT validation has nothing to validate — auth is HTTP Basic and no token is issued (task #25). Rate
limiting needs a shared store, typically Redis, which is not in the compose stack. The module
description was corrected to say so rather than continue to claim them.

---

## 3. Measured result

```
mvn -B --no-transfer-progress verify -DskipITs
→ BUILD SUCCESS
→ 320 unit tests, 0 failures, 0 errors

cd frontend && npm run lint && npm run type-check && npm run build
→ all exit 0
```

| Module | Before | After |
|---|---|---|
| contracts | 9 | 9 |
| common-core | 73 | 73 |
| common-kafka | 25 | 25 |
| common-observability | 17 | 17 |
| common-security | 38 | 38 |
| **ledgerguard-gateway** | **0 (empty module)** | **14** |
| transaction-service | 14 | 14 |
| reconciliation-service | 36 | **76** |
| query-service | 36 | **48** |
| auth-server | 6 | 6 |
| **Total** | **254** | **320** |

---

## 3a. A regression this phase introduced, and how it surfaced

The first push (`1337269`) was green locally and **failed on CI**: all 13 `SagaOrchestratorIT`
tests errored with `Failed to load ApplicationContext`.

Not a saga failure. `SagaOrchestratorIT` boots the whole application but supplies **only a
PostgreSQL container**. Adding a `@KafkaListener` to the service meant Spring now starts a listener
container during context refresh, which tried to reach a broker that the test never provides. The
test's fixture had stopped covering what the application does.

Fixed by disabling listener auto-startup for that test:

```java
registry.add("spring.kafka.listener.auto-startup", () -> "false");
```

Chosen over adding a Kafka container because this test is about saga state transitions against a
real database; standing up a broker would make it slower and less focused without asserting
anything more. It is also the pattern already used by `ProjectionIT` and `AuditChainIT` — the
inconsistency was `SagaOrchestratorIT`, which had never needed it before. `WritePathIT` runs a real
`KafkaContainer` and needs no such property. All four are now consistent.

**Worth naming plainly:** this could not have been caught locally. Integration tests are skipped
here because there is no Docker daemon, so `mvn verify -DskipITs` was green while the branch was
broken. That is the second consecutive phase in which CI found something local runs structurally
cannot — the argument for reading CI after every push rather than assuming green.

---

## 4. Also updated

- `docs/event-catalog.md` — `TransactionReconciled` section, required by the catalog test.
- `docker-compose-full.yml` — gateway service on :8080, routing to the others by service name.
- `.github/workflows/docker-build-scan.yml` — gateway added to the image matrix.
- `frontend` — dashboard renders the three rates, the pending count, and a caption stating which
  figures are sampled and which are exact.

---

## 5. What is still absent or unexecuted

**The honest caveat on the matching outcome.** `ReconcileTransactionHandler.externalCandidatesFor`
returns an empty list, because **no counterparty statement feed exists in this repository**. Every
transaction therefore reconciles as `UNMATCHED`, and a demo will show a 0% match rate. That is a
truthful outcome for a system with no counterparty data, not a placeholder pretending to match —
and the alternative, seeding fake external entries so the number looks good, is exactly the kind of
fabrication Phase 16 was written to stop. The engine, the contract, the projection, the metrics and
the console are all exercised end to end regardless; the candidate source is the one seam a real
feed plugs into, and it is a single method.

**Unchanged from Phase 18, and not attempted this pass:**

1. **`docker compose up` has still never been run.** Individual images build on CI; the composition
   — service wiring, the nginx rule splitting `/api/v1/auth` from `/api`, the new gateway's routing
   against real service names, healthcheck ordering — is unproven. **A Docker-capable machine is
   required and this environment has no daemon**; CI builds images but does not run the stack.
2. **`scripts/demo.sh` has still never been executed against a running system.** Same blocker.
3. **No performance number exists.** k6 is not installed; everything in `perf/` is a target.
4. **Redaction is still called from no service, and no producer emits a traceparent** — the two
   consumers read one that is never written. Task #23.
5. **Search and audit filtering are still in-memory** over a bounded page. Task #24.
6. **Auth is still HTTP Basic**, and the console still holds a replayable credential in
   `localStorage`. Task #25.

**New, smaller items this phase created:**

7. `ReconciliationEventPublisher` publishes directly rather than through an outbox. The outcome is
   durable in the saga tables before the publish, so a failed publish is recoverable — but by an
   operator replaying, not automatically. transaction-service does this properly with a transactional
   outbox; this should match it.
8. The saga orchestrator is still not on the reconciliation path. The new consumer calls the engine
   directly. The saga is real, tested code that models a multi-step workflow the current single-step
   flow does not need — it should either be used or its role documented as future-facing.

---

## 6. Standing rule

Unchanged: a phase report states only what a command actually printed. Where something was not run,
it says so. The 0% match rate a demo will show is real output from a system with no counterparty
feed, and this report says that rather than letting the number be read as a defect.
