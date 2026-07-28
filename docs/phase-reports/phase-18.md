# Phase 18: CI on a Docker-Capable Runner, and the Bug It Found

**Status**: ✅ The integration tests, image builds and frontend pipeline have now
**actually executed**. One real defect was found and fixed.

Every claim in §1–§3 is backed by a GitHub Actions log. §5 says plainly what is still
unexecuted.

---

## 1. Docker: not here, but CI had it all along

`docker info` fails in this environment — the CLI and socket file exist, but no daemon is
running:

```
$ docker ps
Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?
```

So the integration tests still cannot run locally. **But GitHub Actions had a Docker-capable
runner the whole time, and every workflow run had been failing.** Eight runs, all red, going
back to the first push of this branch. Nobody had read them — including me, across three phases
of saying "run CI once on a Docker-capable runner" as the recommended next step while pushing
to a branch whose CI was already running and already failing.

That is the correction worth stating: the highest-value signal was available for hours and I
did not look at it.

---

## 2. What CI proved was already working

Two deferrals carried since Phase 16 are now closed by evidence, not reasoning.

### The integration tests do run, and 9 of 10 pass

`WritePathIT` executed against real Testcontainers Postgres and Kafka on run
`30381840270` — **10 tests, 32 seconds**:

```
✅ canonicalisationIgnoresFieldOrder     ✅ optimisticLockingPreventsLostUpdates
✅ ledgerEntriesBalance                  ✅ failureLeavesNoPartialState
✅ duplicateRequestIsIdempotent          ✅ aggregateAndOutboxAreWrittenAtomically
✅ publishedRecordsAreNotResent          ✅ eventIsPublishedToKafkaAndConsumed
❌ concurrentDuplicatesCreateOneAggregate  ✅ sameKeyDifferentBodyConflicts
```

`eventIsPublishedToKafkaAndConsumed` passing is itself notable: a real event reached a real
Kafka topic and a real consumer read it back.

### The Docker images do build

Phase 16 rewrote the Dockerfiles onto a Maven-bearing base image with the full reactor copied,
and the report was explicit that this was reasoned from base-image contents and **not proven**.
It is proven now. From the buildkit log for `transaction-service`:

```
#16 [build 6/6] RUN --mount=type=cache,target=/root/.m2  mvn -B -q -pl services/transaction-service -am package ...
#16 DONE 22.1s
#17 [stage-1 4/5] COPY --from=build .../transaction-service-*.jar app.jar   DONE 0.0s
#18 [stage-1 5/5] RUN chmod 440 app.jar && chmod 550 /app                   DONE 0.1s
#19 exporting to docker image format ... DONE 1.9s
#20 importing to docker            ... DONE 1.4s
```

The pre-Phase-16 Dockerfile ran `mvn` inside a plain JDK image that has no Maven; it could
never have reached step 16.

### The frontend pipeline passes on a clean checkout

Run `30382896990`, job `frontend`: install → lint → type-check → build, all green in 19s from a
cold clone. The Phase 16 lockfile and config fixes hold outside this container.

---

## 3. The bug CI found: a duplicated financial instruction

```
concurrentDuplicatesCreateOneAggregate
  org.opentest4j.AssertionFailedError: [exactly one request should be accepted]
  expected: 1
   but was: 2
```

Eight threads submit the same body under one `Idempotency-Key`. The controller returns **202
for a fresh accept and 200 for an idempotent replay**, so the counter only counts creations.
Two creations means **two aggregates, two outbox rows, two financial instructions from one
client intent** — the exact failure idempotency exists to prevent.

### Root cause

Not the constraint: `idempotency_key VARCHAR(255) PRIMARY KEY` is present in
`V1__reliability_tables.sql`. The defect was in how the claim was written.

`IdempotencyRecordEntity` has an **assigned `String` id and no `@Version`**, so Spring Data's
`isNew()` returns false and `save()`/`saveAndFlush()` resolve to **`merge()`, not `persist()`**.
`merge()` issues a SELECT first:

- Loser's `findById` pre-check runs before the winner commits → sees nothing → proceeds.
- Winner commits.
- Loser's `saveAndFlush` → `merge()` → SELECT now **finds** the winner's row → issues an
  **UPDATE**, resetting it to `IN_FLIGHT`.
- No constraint violation is ever raised. The loser continues into the write path.

The pre-existing comment at that call site shows `merge()` was known to be in play — but the
conclusion drawn was about which instance to keep, not that the claim itself had stopped being
a claim.

### Fix

`IdempotencyRepository.tryClaim`, one atomic statement:

```sql
INSERT INTO idempotency_record (idempotency_key, endpoint, request_body_hash, state, created_at)
VALUES (:key, :endpoint, :bodyHash, 'IN_FLIGHT', :now)
ON CONFLICT (idempotency_key) DO NOTHING
```

This also removes a second latent problem. A constraint violation marks the transaction
rollback-only, so the follow-up read — the read that produces the correct replay response —
could not legally be done in it. With `ON CONFLICT` the loser gets `0` rows, no exception, and a
usable transaction. PostgreSQL blocks on an uncommitted conflicting row, so by the time
`tryClaim` returns, the winner's record is committed and visible.

### Regression cover

- `theClaimIsAnAtomicInsertNotAMergingSave` — asserts `save`/`saveAndFlush` are never used for
  the claim, so the merge footgun cannot be reintroduced.
- `theLoserWritesNoAggregateAndNoOutboxRow` — the invariant that was actually violated.

transaction-service 12 → 14 tests. Reactor total **254**, 0 failures locally.

**Verified on CI.** Run `30382896990` on `2701a4b`, against real Testcontainers Postgres and
Kafka:

```
services/transaction-service/target/failsafe-reports/TEST-dev.ledgerguard.transaction.WritePathIT.xml
10 tests completed in 27s with 10 passed, 0 failed, 0 skipped
  ✅ concurrentDuplicatesCreateOneAggregate      ← was ❌
  ✅ canonicalisationIgnoresFieldOrder           ✅ optimisticLockingPreventsLostUpdates
  ✅ ledgerEntriesBalance                        ✅ failureLeavesNoPartialState
  ✅ duplicateRequestIsIdempotent                ✅ aggregateAndOutboxAreWrittenAtomically
  ✅ publishedRecordsAreNotResent                ✅ eventIsPublishedToKafkaAndConsumed
                                                 ✅ sameKeyDifferentBodyConflicts
```

The write path's idempotency guarantee is now demonstrated rather than asserted.

---

## 4. The other CI failure was configuration, not code

`docker-build-scan` failed on every run, but **not** because the images failed. Trivy built,
scanned and validated the SARIF; the upload step then failed:

```
##[error]Resource not accessible by integration
##[warning]... ensure the workflow has at least the 'security-events: read' permission
```

Fixed by granting `security-events: write` at the workflow level and making the upload
`continue-on-error` — a repository without code scanning enabled should not fail a build whose
image is sound. `auth-server` was also added to the image matrix now that it is a real service.

---

## 5. What is still unexecuted — unchanged and stated plainly

The work in §1–§4 consumed this pass. The following were **not** attempted and remain exactly
as Phase 17 left them:

1. **`docker compose up` has still never been run.** Individual images build on CI; the compose
   stack — service wiring, the nginx rule splitting `/api/v1/auth` from `/api`, healthcheck
   ordering — is still unproven. CI builds images, it does not run the composition.
2. **`scripts/demo.sh` has still never been executed against a running system.** It was
   rewritten in Phase 17 for the real contracts and remains untested code. Its output in any
   documentation is illustrative, not captured.
3. **No performance number exists.** k6 is not installed; everything in `perf/` is a target.
4. **Reconciliation still publishes no contracted event.** `TransactionReceived` remains the
   only schema, so transaction status never advances past `RECEIVED`, the lifecycle view shows
   one hop, and match/error rate stay uncomputable.
5. **`services/ledgerguard-gateway` is still an empty module** — one `package-info.java`.
6. **Redaction is still called from no service; tracing is still not propagated** — no producer
   emits a traceparent.
Items 4 and 5 were the fallback the deadline instruction named for the case where Docker was
unavailable. Docker was unavailable *locally*, but a Docker-capable runner was available via
CI, so the primary path applied and took the pass. They remain open.

---

## 6. Standing state — both workflows green on `2701a4b`

**`Build and Test`** — first green run on this branch.

| | Result |
|---|---|
| Unit tests | 254 passing, 0 failures |
| Integration tests | **28 passing, 0 failures** |
| `WritePathIT` | 10 tests, 27s, 10 ✅ |
| `SagaOrchestratorIT` | 13 tests, 11s, 13 ✅ |
| `AuditChainIT` | 3 tests, 9s, 3 ✅ |
| `ProjectionIT` | 2 tests, 71ms, 2 ✅ |
| Frontend lint / type-check / build | ✅ from a cold clone |

**`Docker Build and Scan`** — all five jobs green, images built, Trivy scanned, SARIF uploaded:

| Image | Build | Trivy | SARIF upload |
|---|---|---|---|
| transaction-service | ✅ | ✅ | ✅ |
| reconciliation-service | ✅ | ✅ | ✅ |
| query-service | ✅ | ✅ | ✅ |
| auth-server | ✅ | ✅ | ✅ |
| frontend | ✅ | ✅ | ✅ |

Still never executed: `docker compose up`, `scripts/demo.sh`, any performance scenario. Those
are compositions and measurements, not builds — CI does not cover them, and nothing in this
phase changed that.
