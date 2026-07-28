# Phase 17: Connecting the Console to Real Controllers

**Status**: ✅ BUILD GREEN — console and backend now meet; deferrals named in §5

Phase 16 found that the console and the backend did not connect: the React app called nine
endpoints, and exactly one of them existed. This phase closes that gap, adds the write-path unit
tests transaction-service never had, and makes login work end to end.

Everything below was produced by running the command shown. Where something has not been run, it
says so.

---

## 1. Measured result

```
mvn -B --no-transfer-progress verify -DskipITs
→ BUILD SUCCESS
→ 252 unit tests, 0 failures, 0 errors

cd frontend && npm run lint && npm run type-check && npm run build
→ all exit 0
→ dist/assets/index-*.js   225.92 kB │ gzip: 74.63 kB
```

| Module | Before this phase | After |
|---|---|---|
| contracts | 9 | 9 |
| common-core | 73 | 73 |
| common-kafka | 25 | 25 |
| common-observability | 17 | 17 |
| common-security | 38 | 38 |
| transaction-service | **0** | **12** |
| reconciliation-service | 36 | 36 |
| query-service | 17 | **36** |
| auth-server | **0 (empty module)** | **6** |
| **Total** | **215** | **252** |

---

## 2. The endpoints the console calls now exist

Nine of ten calls previously 404'd. Every one now has a controller, and every controller is
covered by an authorization test.

| Endpoint | Status before | Backed by |
|---|---|---|
| `GET /transactions/search` | 404 | `TransactionQueryController` → Transaction 360 projection |
| `GET /transactions/{id}` | 404 | same |
| `GET /transactions/{id}/lifecycle` | 404 | same |
| `GET /transactions/by-correlation/{id}` | *(new)* | same |
| `GET /replay/dlt-messages` | 404 | `DltQueryController` → new `dlt_message` table |
| `POST /replay/dlt-message` | existed | `ReplayController` (unchanged) |
| `GET /audit/entries` | 404 | `AuditQueryController` → audit chain |
| `GET /audit/entries/{correlationId}` | 404 | same |
| `GET /audit/verify` | *(new)* | `AuditChainService.verify()` |
| `GET /metrics/dashboard` | 404 | `DashboardMetricsService` |
| `POST /auth/login` | 404 | `auth-server`, newly built |

### The DLT explorer had no data source at all

Dead letters existed only on the Kafka topic — they aged out with the retention policy and nothing
could list them. Added:

- `V3__dlt_message.sql`, with a unique index on `(source_topic, partition, offset)` so a
  rebalance redelivering the same record does not inflate the depth an operator sees.
- `DltConsumer`, which captures dead letters into that table. A message that is *not* a
  well-formed retry envelope is still captured, with the parse failure as its reason — an operator
  needs to see a corrupt message more than a well-formed one.

---

## 3. Two real bugs found by the tests written here

**Role hierarchy was never applied.** `@PreAuthorize("hasRole('USER')")` means "holds the
`ROLE_USER` authority literally". An administrator holds `ROLE_ADMIN` and nothing else, so **an
admin would have been denied every endpoint gated below their own rung** — logging in as admin and
opening the console would have 403'd on every page.

This was masked in Phase 16's own test suite: `adminCanReplayBecauseTheHierarchyIncludesOperations`
granted the admin fixture *both* `ROLE_ADMIN` and `ROLE_OPERATIONS`, so it passed while the
hierarchy was not wired. That test fixture has been corrected to grant `ROLE_ADMIN` only, and it
now passes for the right reason. The fix is a `RoleHierarchy` bean plus a
`MethodSecurityExpressionHandler` — declaring the hierarchy alone is not enough, because method
security builds its own expression handler and ignores it otherwise. The chain is derived from the
`Role` enum's declaration order so the two definitions cannot drift.

**Compose healthchecks could never pass.** Every service healthcheck ran `curl`, which is not
present in the `eclipse-temurin:21-jre-alpine` runtime images. All would have failed permanently,
holding dependent services in `starting` forever. Changed to `wget`, which the Dockerfiles install.

---

## 4. Login works end to end — and what it actually is

`services/auth-server` was an empty module containing one `package-info.java`. It is now a running
Spring Boot service with `POST /api/v1/auth/login`, backed by `UserCatalog` in `common-security` so
the auth server and the resource services cannot disagree about who exists.

**Be precise about what it returns: it is not a token.** It returns the caller's HTTP Basic
credential, base64-encoded, because Basic is what the services actually accept. It has no expiry,
no signature and no revocation path. The console stores it in `localStorage` and replays it.

The consequence, stated plainly: **the console holds a replayable credential in browser storage,
and the demo passwords are equal to the usernames.** That is fine for a local demo and not fine for
production. The upgrade is this endpoint minting a short-lived signed JWT and the services
validating it as a bearer token — the authorization decisions downstream do not change, only where
identity comes from.

A related bug fixed on the console side: `client.ts` sent `Authorization: Bearer …`. Nothing in
this system has ever issued or validated a bearer token, so every authenticated call would have
been rejected. It now uses the scheme the login response names.

---

## 5. What is deferred — named, not skipped

**Still cannot be verified here (no Docker, no k6):**

1. **The 4 integration tests have still never run.** `WritePathIT`, `SagaOrchestratorIT`,
   `ProjectionIT`, `AuditChainIT` compile; their outcome is unknown.
2. **No image has ever been built.** The Dockerfiles — including the new `auth-server` one — are
   reasoned from base-image contents, not proven. Assume the first `docker build` needs a round of
   fixes.
3. **`docker compose up` has never been run.** The compose wiring, the nginx proxy rule splitting
   `/api/v1/auth` from `/api`, and the Vite dev-proxy split are all unexercised.
4. **`scripts/demo.sh` has never been executed against a running system.** It was rewritten this
   phase to use the real login flow, the real ingestion contract (including the required
   `Idempotency-Key` header), the real ports and the real response shapes — but it is still
   untested code.
5. **No performance number exists.** Everything in `perf/` remains a target.

**Deliberate simplifications, with the reason:**

6. **Search and audit filtering happen in the service, not the database.** Both read a bounded page
   and narrow it in memory. Correct, and it does not scale — a text index on the projection and an
   indexed actor query are the real fixes. Chosen because it makes the console work today without a
   schema change.
7. **DLT paging honours only the first page.** The `offset` parameter is accepted and effectively
   coerced to a page number. Keyset paging over `recorded_at` is the intended fix and the index for
   it already exists in `V3`.
8. **Three dashboard figures are absent rather than faked.** Consumer lag needs a Kafka
   `AdminClient` this service does not have. Match rate and error rate need a terminal outcome per
   transaction — the projection only ever sets `RECEIVED`, because `TransactionReceived` is the
   only contracted event type, so there is no matched or failed population to divide by. Reporting
   "100% matched" off an empty numerator would be worse than reporting nothing. The console now
   states what its numbers are sampled from instead.
9. **Lag and last-hour counts are sampled**, over the 200 most recent transactions, not the whole
   collection. The response carries `sampleSize` and the console prints it, so a sampled figure is
   not read as an exhaustive one.
10. **Basic auth, not JWT** — see §4.

**Still not built:**

11. **`services/ledgerguard-gateway` is still an empty module** — one `package-info.java`. Nothing
    routes through a gateway; the console talks to the query service and auth server directly.
12. **Reconciliation still publishes no contracted event.** `TransactionReceived` is the only
    schema, so a transaction's status never advances past `RECEIVED` and the lifecycle timeline
    shows one hop. The matching engine exists and is well tested in isolation; it is not connected
    to the projection by a versioned event.
13. **Redaction is still not called from any service**, and **tracing is still not propagated** —
    no producer emits a traceparent. Both were on Phase 16's list and neither was in this phase's
    scope.
14. **transaction-service has no controller test**; the 12 new tests cover the handler.

---

## 6. Recommended order from here

1. Run CI once on a Docker-capable runner — resolves items 1–3 and much of 4.
2. Give reconciliation a contracted event so status advances past `RECEIVED`; that unblocks match
   rate and error rate (item 8) and makes the lifecycle view worth looking at (item 12).
3. Replace the in-memory search filter with an indexed query (item 6) before any dataset grows.
4. Swap Basic for a signed JWT (item 10) — the authorization logic does not change, so this is
   contained.
