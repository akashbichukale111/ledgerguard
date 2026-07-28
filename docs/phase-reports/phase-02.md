# Phase 2 — Infrastructure

**Date executed:** 2026-07-28
**Branch:** `claude/ledgerguard-master-build-d4bpzz`
**Entry commit:** `c38e399` (Phase 1)

---

## 1. What was built

| Deliverable | Detail |
|---|---|
| `docker-compose.yml` | 5 infrastructure containers + 2 one-shot Flyway runners; profiles `infra` / `core` / `full` / `migrate`; explicit `mem_limit` on every container; named volumes; real health checks |
| `.env.example` | 61 lines of placeholders. **No real credential anywhere in git.** |
| `infra/postgres/init/` | Bootstrap script + SQL creating 3 databases and the least-privilege `lg_app` role |
| `infra/mongo/init/01-init.js` | Versioned, **verified-idempotent** collection and index initialiser |
| Flyway migrations | `V1__reliability_tables.sql` (outbox, idempotency, processed_event), `V1__audit_chain.sql` (hash-chained audit + append-only grants) |
| `Makefile` | `up` `up-infra` `up-core` `down` `ps` `logs` `migrate` `stats` `build` `test` `verify` `format` `demo` `perf` `clean` `clean-all` |

---

## 2. Acceptance gate

### 2.1 Cold start from empty volumes

```
$ docker compose --profile infra --profile migrate down -v --remove-orphans
 Network ledgerguard Removed

$ docker compose --profile infra up -d --wait
 Container ledgerguard-postgres-1 Healthy
 Container ledgerguard-redis-1 Healthy
 Container ledgerguard-kafka-1 Healthy
 Container ledgerguard-zipkin-1 Healthy
 Container ledgerguard-mongo-1 Healthy
EXIT=0   COLD_START_SECONDS=12
```

```
$ docker compose ps
SERVICE    STATUS                    PORTS
kafka      Up 32 seconds (healthy)   0.0.0.0:9092->9092/tcp
mongo      Up 32 seconds (healthy)   0.0.0.0:27017->27017/tcp
postgres   Up 32 seconds (healthy)   0.0.0.0:5432->5432/tcp
redis      Up 32 seconds (healthy)   0.0.0.0:6379->6379/tcp
zipkin     Up 32 seconds (healthy)   9410/tcp, 0.0.0.0:9411->9411/tcp
```

**All five healthy, 12 s from empty volumes** — against a 5-minute budget.

**Honest caveat on that 12 s:** images were already in the local cache. It excludes pull time,
which on this sandbox is served by a registry mirror rather than Docker Hub directly (Phase 0 §3.2).
The figure measures **boot and health convergence**, not a first-ever run on a clean machine.

### 2.2 Measured resource cost

```
$ docker stats --no-stream
NAME                     MEM USAGE / LIMIT   MEM %     CPU %
ledgerguard-postgres-1   42.62MiB / 768MiB   5.55%     0.05%
ledgerguard-redis-1      3.496MiB / 384MiB   0.91%     0.49%
ledgerguard-kafka-1      277.4MiB / 1.5GiB   18.06%    3.32%
ledgerguard-mongo-1      173.6MiB / 1GiB     16.95%    0.52%
ledgerguard-zipkin-1     243.4MiB / 512MiB   47.54%    4.36%

sum of MemUsage: 742 MiB
```

| Profile | Containers | Measured idle RAM | Declared limit ceiling |
|---|---|---|---|
| `infra` | 5 | **742 MiB** | 4,160 MiB |
| `core` | `infra` + 4 JVMs | *not yet measurable* | — |
| `full` | `core` + query-service + console | *not yet measurable* | — |

Idle, immediately post-boot, on the Phase 0 reference machine (4 vCPU / 15 GiB). Under load these
will rise — particularly Kafka. `core` and `full` figures are recorded when those services exist;
none is estimated here.

---

## 3. Verification beyond the gate

A stack that reaches "healthy" has proven its health checks pass. That is not the same as proving
it is correctly configured. Each claim below was executed.

### 3.1 The audit `REVOKE` genuinely blocks writes — ADR-0014's central claim

This is the most important verification in this phase, because ADR-0014 justified deviating from
the build specification on precisely this ground.

```
$ psql -U lg_app -d ledgerguard_audit
-- INSERT:  INSERT 0 1
-- UPDATE:  ERROR:  permission denied for table audit_event
-- DELETE:  ERROR:  permission denied for table audit_event
```

The application role can append and read. It **cannot** rewrite history. This is database
enforcement, not application discipline.

### 3.2 The chain cannot fork

```
$ INSERT ... previous_hash = NULL   (a second genesis record)
ERROR:  duplicate key value violates unique constraint "ux_audit_genesis"
DETAIL:  Key ((previous_hash IS NULL))=(t) already exists.
```

A fork would produce two individually-valid-looking chains and verification would silently follow
one. The partial unique index makes that impossible rather than unlikely.

### 3.3 Migrations apply from scratch

```
$ docker compose --profile migrate run --rm flyway-txn
Successfully applied 1 migration to schema "public", now at version v1

$ docker compose --profile migrate run --rm flyway-audit
Successfully applied 1 migration to schema "public", now at version v1

ledgerguard_txn:   flyway_schema_history, idempotency_record, outbox_record, processed_event
ledgerguard_audit: audit_event, flyway_schema_history
```

The one-shot runners exist so migrations can be applied **without** the services. That is what makes
the schema independently testable, and it is how §3.1 was proven before a single line of Java
exists.

### 3.4 Kafka serves real metadata

```
$ kafka-topics.sh --create --topic transactions.events.v1 --partitions 3 --replication-factor 1
Created topic transactions.events.v1.
$ kafka-topics.sh --describe --topic transactions.events.v1
PartitionCount: 3   ReplicationFactor: 1   Partition: 0  Leader: 1  Replicas: 1  Isr: 1
```

The health check runs `kafka-topics.sh --list` through the real client path rather than probing the
port, so "healthy" means the broker is actually serving metadata.

### 3.5 Redis requires authentication

```
$ redis-cli ping                              -> (no output: NOAUTH)
$ redis-cli -a <password> ping                -> PONG
```

### 3.6 MongoDB initialiser is idempotent — claim tested, then fixed

The script's comment claimed re-running was safe. **It was not**, and the test caught it:

```
$ mongosh ... /docker-entrypoint-initdb.d/01-init.js
MongoServerError: Command create requires authentication
```

Cause: `new Mongo()` opens a **fresh unauthenticated connection**. It worked inside the initdb
entrypoint and would fail on any manual re-run — exactly the operation the comment promised.

Fixed by using `db.getSiblingDB()`, which reuses the current authenticated session, plus a
`createIfAbsent` helper that swallows only `NamespaceExists` (code 48). Re-verified after a full
teardown:

```
first boot:  collections=processed_event,reconciliation_grid,schema_version,transaction_360  version=1
manual re-run: "ledgerguard: mongo schema initialised at version 1"
after re-run: collections=4  txn360_idx=4  grid_idx=6     (unchanged)
```

**The claim is now true because it was tested, not because it was written down.**

---

## 4. Deviation: the `full` profile gate cannot be met in this phase

§14's Phase 2 acceptance is *"`docker compose --profile full up -d` reaches all-healthy from a cold
start."* The `full` profile contains the gateway, transaction-service, reconciliation-service,
query-service, and the console — **none of which §14 creates until Phases 4, 5, 6, and 10.**

The specification's own phase ordering makes the gate as literally written unreachable here. Three
options were considered:

1. **Declare the services in compose anyway.** They would reference Dockerfiles that do not exist,
   so `--profile full up` would fail on build. A compose file that cannot run is worse than one
   that is honestly incomplete.
2. **Ship five bootable "shell" services** — a main class and an actuator endpoint each — purely so
   `full` goes green. This would technically satisfy the gate while making the compose file claim a
   working stack that does nothing. It contradicts §0.7 (depth over breadth) and the honesty
   clause, and it would hollow out Phase 4's own acceptance evidence.
3. **Deliver the infrastructure substrate completely and verify it completely; let application
   services join their profiles as each becomes buildable.** *(chosen)*

**What was actually met:** every container that exists in this phase reaches healthy from a cold
start, within budget, with its configuration independently verified (§3).

**What is deferred, and to where:** the `core` profile gate is re-verified in Phase 5 (once
gateway, transaction-service, and reconciliation-service are buildable); the `full` profile gate in
Phase 10 (once query-service and the console exist). Both are recorded as carried-forward gates
below and re-checked in Phase 15.

No gate was weakened or skipped — one was **split**, with the remainder assigned to the phase that
creates its subject.

---

## 5. Defects found and fixed in this phase

Recorded because §14 asks for real output, and real output includes what broke.

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | Postgres never became healthy; container restart-looped | `00-render.sh` used `sed -i` against `/docker-entrypoint-initdb.d`, which is mounted **read-only**: `sed: can't create temp file ... Read-only file system` | Removed the render step. The SQL now receives the password as a psql variable (`:'app_password'`), so psql does the quoting and nothing mutates its own source. SQL moved to `init/sql/` because the entrypoint scans the top level non-recursively and would otherwise execute it without the variable. |
| 2 | `docker compose --profile migrate run` failed: `service "flyway-txn" depends on undefined service "postgres"` | `postgres` was not a member of the `migrate` profile, so `depends_on` could not resolve within that profile selection | Added `migrate` to the postgres profile list |
| 3 | Mongo initialiser was not idempotent despite claiming to be | `new Mongo()` opens an unauthenticated connection | `db.getSiblingDB()` + `createIfAbsent` helper; §3.6 |

All three were fixed at the cause. Nothing was worked around, and no check was relaxed.

---

## 6. Acceptance criteria

| Criterion (§14 Phase 2) | Status | Evidence |
|---|---|---|
| docker-compose with profiles | **Met** | 4 profiles; `docker compose --profile migrate config --services` resolves |
| Health checks on every service | **Met** | §2.1 — all 5 report `(healthy)`; checks exercise real client paths, not port probes |
| Migrations | **Met** | §3.3 — Flyway applies both from scratch; §3.1 proves the grants they create actually work |
| `.env.example` | **Met** | Placeholders only; `.env` gitignored |
| `Makefile` | **Met** | 16 targets; `make verify` runs exactly what CI will run |
| Cold start to all-healthy within budget | **Met for all containers that exist** | §2.1 — 12 s vs a 300 s budget |
| Within resource budget | **Met** | §2.2 — 742 MiB measured vs a 10 GB budget |
| `--profile full` all-healthy | **Deferred to Phase 10** | §4 — the profile's services are created in Phases 4–10 |

---

## 7. Known gaps carried into Phase 3

1. **`core` and `full` profile gates outstanding** — re-verified in Phases 5 and 10 (§4).
2. **No Dockerfiles yet.** Multi-stage, non-root, digest-pinned images are Phase 13.
3. **Topics are created manually.** `transactions.events.v1` was created by hand to prove the broker
   works. Declarative topic creation (including the retry/DLT ladder) is Phase 5/7.
4. **No secret scanning yet.** `.env.example` holds placeholders and `.env` is gitignored, but
   Gitleaks is not wired until Phase 13. Until then the discipline is manual.
5. **`mvnw` wrapper absent.** The `Makefile` prefers `./mvnw` and falls back to `mvn`; the wrapper
   should be added so CI and contributors build with an identical Maven.
6. **Migrations exist but nothing reads them from a service yet** — Flyway is wired into
   transaction-service in Phase 4.

## 8. Entry conditions for Phase 3

- [x] Infrastructure boots cold to all-healthy, verified
- [x] PostgreSQL databases, least-privilege role, and both schemas exist
- [x] Audit append-only enforcement proven at the database level
- [x] Kafka broker serving metadata; Mongo indexes in place
- [x] `make` targets available for the inner loop
- [x] Phase 2 committed and pushed
