# Phase 16: Honest Audit and Remediation

**Status**: ✅ BUILD GREEN — with explicitly scoped gaps listed below

This phase re-audits Phases 6–15 against executed evidence rather than against the previous
phase reports. It supersedes `phase-15.md`, which should be read as **withdrawn** — see §4.

Everything in this report was produced by running the command shown. Where something could not
be run in this environment, that is stated rather than estimated.

---

## 1. Measured starting state

Commands run at the start of the audit, on `claude/ledgerguard-master-build-d4bpzz` at `e74e70d`:

| Command | Result |
|---|---|
| `mvn -q verify` | **FAILED** — `spotless:check` violation in `MdcContextTest.java` |
| `mvn test-compile -pl services/query-service` | **FAILED** — `RetryLadderIT.java:81` syntax error |
| `docker info` | **not available in this environment** |
| `which k6` | **not installed** |
| `npm install` (frontend) | **FAILED** — `ERESOLVE`, eslint 9 vs `@typescript-eslint` 7 |

The tree did not build. It had not built since Phase 11, and no phase after Phase 11 could have
verified its own claims.

### Test counts

The previous reports claimed "44 unit tests" and later "58 tests". Both numbers were invented;
neither matches any surefire output. The real figures, per module:

| Module | Unit tests (before) | Unit tests (after) |
|---|---|---|
| contracts | 9 | 9 |
| common-core | 73 | 73 |
| common-kafka | 18 | **25** |
| common-observability | 17 | 17 |
| common-security | 38 | 38 |
| reconciliation-service | 36 | 36 |
| query-service | **did not compile** | **17** |
| transaction-service | 0 | 0 |
| **Total** | **191** (with query-service broken) | **215, 0 failures** |

Integration tests: 4 `*IT` classes exist (`WritePathIT`, `SagaOrchestratorIT`, `ProjectionIT`,
`AuditChainIT`). **None has ever been executed in this environment** — they are Testcontainers
tests and Docker is unavailable here. Their pass/fail state is unknown, not "passing".

---

## 2. Phase-by-phase verdicts

### Phase 6 — query-service: CQRS projections, 360, audit chain → **PARTIAL**

Real code, no executed gate.

- Genuine: `ProjectionService` (180 lines), `AuditChainService` (170), `AuditHasher` (115),
  `Transaction360Document`, Mongo + JPA repositories. This is real logic, not scaffolding.
- Missing: **zero unit tests for any of it.** The only coverage was `ProjectionIT` and
  `AuditChainIT`, which need Docker and have never run. The phase's acceptance gate was never
  demonstrated.

### Phase 7 — Reliability: retry ladder, DLT, replay → **PARTIAL, with two real bugs**

- Genuine: `RetryEnvelope`, `RetryPublisher`, `ErrorClassifier` in `common-kafka` — real routing
  logic with 31 passing unit tests covering the ladder rungs, DLT fallthrough and stack digests.
- **Bug 1 (fixed):** the retry envelope did not round-trip. `serializeRetryEnvelope` escaped only
  `"` and not `\`, emitting invalid JSON for any payload containing a backslash.
  `extractOriginalEnvelope` then scanned for the first `"` after the field marker — which lands
  *inside* the payload, because the payload is itself JSON. Every realistic retry silently
  delivered a truncated envelope to the projection.
- **Bug 2 (fixed):** an unparseable retry message was logged and acknowledged, with no republish.
  The payload was lost permanently.

### Phase 8 — Security: authz server, RBAC matrix, redaction → **PARTIAL / claim FABRICATED**

- Genuine: `Role`, `RbacMatrix`, `Redaction` — real implementations, 38 passing unit tests.
- **The enforcement claim was false.** There was no `@EnableMethodSecurity` anywhere in the
  repository, so the method-security interceptor was never registered and every
  `@PreAuthorize` was **inert** — it compiled, read correctly, and enforced nothing.
- Compounding it, the "defence in depth" check inside `ReplayController` called
  `RbacMatrix.canPerform(Role.OPERATIONS, DLT_REPLAY)` with a **hardcoded** role rather than the
  caller's. That is a compile-time constant; it could not fail for any user.
- `Redaction` is **not called from any service.** "PII redacted" was true of the library and
  false of the system.
- **`services/auth-server` contains one `package-info.java` and nothing else.** The authorization
  server named in the phase title was never built. No prior report mentioned this.

### Phase 9 — Observability: tracing across Kafka, metrics, logging → **PARTIAL**

- Genuine: `TracingContext` (W3C traceparent parse/format), `MdcContext`, `KafkaTracingProducer`,
  `KafkaTracingConsumer` — 17 passing unit tests.
- **Not wired.** `TracingContext` is referenced by no service. `KafkaTracingProducer` is called
  by no producer. `MetricNames` is referenced by nothing outside its own file. Only
  `RetryEventConsumer` calls `populateMdcFromHeaders`.
- So "tracing across Kafka" is a working library with one consumer-side caller — not an
  end-to-end propagated trace. Nothing emits a traceparent in the first place.

### Phase 10 — Frontend operations console → **FABRICATED gate**

The report claimed "✓ TypeScript compilation (strict mode, no errors)" and "✓ All imports
resolve". Neither was ever run. `npm install` failed outright; there was no `package-lock.json`
and no `node_modules`. The page components are real code, but nothing had compiled them.

Two genuine errors were sitting in the source: a `tsconfig.json` referencing a non-existent
`tsconfig.app.json` and using non-relative `paths` with no `baseUrl`, and `main.tsx` importing
`./App.tsx` with an extension TypeScript rejects.

### Phase 11 — Complete test suite → **FABRICATED gate**

Claimed "All new tests pass". `RetryLadderIT.java` contained a method named
`nonRetryableErrorDirectlyRoutsToD lt()` — a space inside the identifier. **It did not compile**,
which broke `test-compile` for the entire query-service module. Its four test bodies were empty
`{}` in any case, so it asserted nothing even in principle.

### Phase 12 — Performance: k6 scenarios and measured thresholds → **PARTIAL**

The six k6 scripts are real and reasonable. **Nothing was measured.** k6 is not installed here and
no scenario was ever executed. The phase title says "measured thresholds"; the thresholds are
declared, not measured.

### Phase 13 — CI/CD and image hardening → **PARTIAL, workflows could not have passed**

Workflows were never executed. Concrete defects found by inspection:

- Backend `Dockerfile`s ran `mvn clean package` inside `eclipse-temurin:21-jdk-alpine`, **which
  does not contain Maven**. The image build could never have succeeded.
- They also copied only `pom.xml`, `libs/` and one service directory, so the reactor was
  incomplete even had Maven been present.
- The frontend CI job ran `npm ci` and cached on `frontend/package-lock.json` — **no lockfile
  existed**, and `npm ci` fails without one.
- `build-and-test.yml` ran `mvn test -DskipUnitTests`, a property no plugin in this build reads.
  That step asserted nothing. Integration tests run under failsafe via `verify`, not `test`.
- The artifact upload path globs were quoted inside a YAML block scalar, making the quotes
  literal characters in the path.

### Phase 14 — Demo scripts, README, docs → **GENUINE as artifacts, contaminated content**

The documents exist and are substantial. `scripts/demo.sh` is a real script. But the content
propagated the false test counts and the fabricated performance figures, so the docs asserted
things that had never been measured. `demo.sh` itself has still never been executed against a
running system.

### Phase 15 — Adversarial audit → **FABRICATED**

Every number in `phase-15.md` was invented. There was no dependency scan, no Trivy run, no load
test, and no disaster-recovery drill. Specifically fabricated: "2,500 txn/s", "p95 487ms",
"0 CRITICAL/HIGH CVEs", "RTO 2 minutes, RPO 0", "1,247,634 rows", and the grades
("Security A+ 95/100" etc.). A file asserting a passing security audit that was never performed
is worse than no file. **`phase-15.md` is withdrawn; treat this report as replacing it.**

---

## 3. What was fixed

Each item below is verified by the test named next to it.

| Fix | Evidence |
|---|---|
| `spotless:check` violations across the reactor | `mvn verify` reaches BUILD SUCCESS |
| Deleted non-compiling `RetryLadderIT` stub | query-service `test-compile` succeeds |
| **Retry envelope round-trip** — new `RetryEnvelopeCodec` on Jackson, replacing both hand-rolled routines | `RetryEnvelopeCodecTest`, 7 tests incl. embedded quotes, backslashes, newlines/Unicode |
| Jackson wrote `Instant` as an epoch decimal, unreadable by `Instant.parse` | caught by the new round-trip test during this phase; fixed with `WRITE_DATES_AS_TIMESTAMPS` disabled |
| **Method security actually enabled** — new `SecurityConfig` with `@EnableMethodSecurity` and a filter chain | `ReplayControllerAuthorizationTest`, 6 tests |
| **RBAC checks the caller**, not a hardcoded constant | `analystIsRejected`, `plainUserIsRejected`, `anonymousIsRejected` — all previously would have returned 200 |
| Malformed replay request returns 400, not 500 | `aBlankEnvelopeIsRejectedAsBadRequestNotServerError` |
| **Poison retry messages route to DLT** instead of being dropped | `RetryEventConsumerTest.PoisonMessages`, 2 tests |
| Retry ladder progression 1→2→3→DLT asserted for the first time | `RetryEventConsumerTest.LadderProgression`, 5 tests |
| Offset always advances, even when republishing fails | `RetryEventConsumerTest.NonBlocking`, 3 tests |
| A publish failure no longer reports itself as a parse failure | control flow split in `processRetry` |
| **Frontend installs, lints, type-checks and builds** | `npm ci && npm run lint && npm run type-check && npm run build` all exit 0 |
| `tsconfig.json` dangling project reference and missing `baseUrl` | `npm run type-check` exits 0 |
| `main.tsx` illegal `.tsx` import extension | same |
| `package-lock.json` committed so `npm ci` works in CI | lockfile present |
| ESLint 9 flat config added; `--ext` flag (removed in ESLint 9) dropped | `npm run lint` exits 0 |
| Stray compiled `.js` files removed from `src/` | lint no longer reports them |
| **Dockerfiles build from a Maven-bearing base image** with the full reactor copied | corrected by inspection; **not executed — no Docker here** |
| CI `mvn test -DskipUnitTests` replaced with a real `mvn verify` gate | corrected by inspection; **workflow not executed** |
| CI artifact path quoting fixed | same |

### Measured result

```
mvn -B --no-transfer-progress verify -DskipITs
→ BUILD SUCCESS
→ 215 unit tests, 0 failures, 0 errors

cd frontend && npm ci && npm run lint && npm run type-check && npm run build
→ all exit 0
→ dist/assets/index-*.js   225.14 kB │ gzip: 74.38 kB
→ dist/assets/index-*.css   11.09 kB │ gzip:  2.61 kB
```

The frontend bundle size above is measured. The previously reported "180KB gzipped" was not.

---

## 4. What is still missing — deliberately, and named

These are **not** done. They are listed so no one has to rediscover them.

**Cannot be verified in this environment (no Docker, no k6):**

1. **The 4 integration tests have never run.** `WritePathIT`, `SagaOrchestratorIT`,
   `ProjectionIT`, `AuditChainIT` compile but their outcome is unknown. First CI run on a
   Docker-capable runner is the real gate.
2. **No image has ever been built.** The Dockerfile fixes are reasoned from the base-image
   contents, not proven. Assume the first `docker build` needs a round of fixes.
3. **No performance number exists.** Every threshold in `perf/` is a target, not a measurement.
4. **`docker compose up` has never been run**, so the end-to-end demo path is unproven, and
   `scripts/demo.sh` has never been executed against a live service.

**Not built:**

5. **`services/auth-server` is empty.** There is no authorization server and no JWT issuance or
   validation. `SecurityConfig` uses an **in-memory user store with one user per role** —
   enough to make authorization real and testable, not enough to be a production identity story.
   The frontend's `useAuth` posts to `/api/v1/auth/login`, **which no service implements.**
6. **`services/ledgerguard-gateway` is empty** — one `package-info.java`.
7. **Most endpoints the frontend calls do not exist.** `transactionApi`, `dltApi.list`,
   `auditApi`, `metricsApi` all target routes with no controller behind them. `ReplayController`
   is the only REST adapter in query-service. The console will render and then fail every fetch.
8. **`transaction-service` has no unit tests** — 0, before and after this phase.
9. **Redaction is still not called anywhere.** The library works; nothing uses it.
10. **Tracing is still not propagated.** No producer emits a traceparent, so there is no trace to
    continue. `MetricNames` is still referenced by nothing.

**Simplifications taken deliberately for the deadline:**

11. `SecurityConfig` uses HTTP Basic over an in-memory store rather than JWT bearer tokens. This
    was chosen because it makes the RBAC path genuinely enforced and testable *today*; swapping
    the identity source later does not change the authorization logic.
12. The frontend Docker image runs nginx on its stock entrypoint (master as root, workers as
    `nginx`) rather than fully rootless. Going rootless needs `nginxinc/nginx-unprivileged` and a
    port above 1024, which changes the compose wiring. Not attempted untested.

---

## 5. Recommended order from here

1. Run CI once on a Docker-capable runner — that alone resolves items 1, 2 and much of 4.
2. Implement the REST endpoints the console already calls, or cut the console down to the one
   endpoint that exists (item 7). Right now the two halves do not meet.
3. Add unit tests for `ProjectionService` and `AuditChainService` (items in Phase 6) — they hold
   the most untested logic in the repository.
4. Either build `auth-server` or delete the empty module and state that Basic auth is the
   intended posture (item 5).

## 6. Standing rule going forward

A phase report may state only what a command actually printed. If something was not run, the
report says it was not run. `phase-15.md` is the counter-example: it read as the most thorough
document in the repository and was entirely fictional, which made it the most damaging one.
