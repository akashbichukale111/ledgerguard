# Phase 0 — Inspection, Feasibility, and Plan

**Date executed:** 2026-07-27
**Branch:** `claude/ledgerguard-master-build-d4bpzz`
**Baseline commit inspected:** `5186818` ("Initial commit")

---

## 1. Repository inspection

The repository was effectively empty at the start of this phase. Complete tracked contents:

```
$ git ls-files
.gitignore
LICENSE
README.md
```

| File | Assessment | Action |
|---|---|---|
| `LICENSE` | Apache License 2.0, unmodified. | **Preserved as-is.** No change planned. |
| `README.md` | Two lines: project name and a one-sentence description. The description is accurate and matches the target architecture. | **Preserved and extended.** Rewritten in Phase 14 per §12; nothing is deleted, the existing description is folded into the new opening. |
| `.gitignore` | GitHub's stock `Java.gitignore`. Correct but incomplete for this project — it ignores `*.jar` (which would exclude the Maven wrapper JAR) and has no entries for `target/`, `node_modules/`, `.env`, IDE files, or test output. | **Extended, not replaced,** in Phase 1. The `*.jar` rule will get a negation for `!**/maven-wrapper.jar` if a wrapper is added. |

**Nothing in the repository was deleted or overwritten in this phase.**

---

## 2. Toolchain verification

All versions below are from commands executed in this environment, not assumed.

### Toolchain versions (executed 2026-07-27)
```
$ java -version
openjdk version "21.0.10" 2026-01-20
OpenJDK Runtime Environment (build 21.0.10+7-Ubuntu-124.04)
OpenJDK 64-Bit Server VM (build 21.0.10+7-Ubuntu-124.04, mixed mode, sharing)

$ mvn -version
Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b)
Maven home: /opt/maven
Java version: 21.0.10, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en_US, platform encoding: UTF-8

$ docker --version && docker compose version
Docker version 29.3.1, build c2be9cc
Docker Compose version v5.1.1

$ node --version && npm --version
v22.22.2
10.9.7

$ nproc && free -h
4
               total        used        free      shared  buff/cache   available
Mem:            15Gi       776Mi        11Gi       4.2Mi       3.9Gi        14Gi

$ df -h /
/dev/vda        252G  9.9G   28G  27% /
```

**Verdict:** JDK 21, Maven 3.9, Docker Engine 29.3 + Compose v5, and Node 22 are all present and usable. The toolchain requirement of §0 is satisfied.

### Reference machine (record this — every performance number in this repository must cite it)

| Property | Value |
|---|---|
| vCPU | 4 |
| RAM | 15 GiB total (~14 GiB available at rest) |
| Disk free at Phase 0 | 28 GB |
| Kernel | Linux 6.18.5 |
| Storage driver | `overlayfs` (containerd snapshotter) |
| cgroup | v1 (deprecated; `docker info` warns), **no cpuset support** |

**This is 4 cores, not the 6 assumed by the prompt's §0.1 budget.** Cold-start timings will therefore be slower than a 6-core laptop. Every measured number committed to this repository will state this machine.

---

## 3. Blocking findings and how they were resolved

### 3.1 Docker daemon was not running (resolved)

`docker` CLI was installed but there was no daemon:

```
$ docker info
failed to connect to the docker API at unix:///var/run/docker.sock; check if the
path is correct and if the daemon is running: dial unix /var/run/docker.sock:
connect: no such file or directory
```

`dockerd` and `containerd` binaries exist at `/usr/bin/` and the session runs as `uid=0(root)`, so the daemon was started directly:

```
$ nohup dockerd > /var/log/dockerd.log 2>&1 &
$ docker info --format 'server={{.ServerVersion}} driver={{.Driver}} cpus={{.NCPU}} mem={{.MemTotal}}'
server=29.3.1 driver=overlayfs cpus=4 mem=16856244224
```

**This is an environment fact, not a repository property.** The daemon must be started again if the session container restarts. It is recorded in `docs/local-development.md` as a sandbox-only note, and is *not* a step real users need.

### 3.2 Docker Hub blob CDN is blocked by egress policy (resolved via allowed mirror)

The first image pull failed. Manifests resolve, but blob fetches are denied:

```
$ docker run --rm postgres:16-alpine postgres --version
16-alpine: Pulling from library/postgres
docker: failed to copy: httpReadSeeker: failed open: failed to do request:
Get "https://production.cloudfront.docker.com/registry-v2/docker/registry/v2/blobs/...":
Forbidden
```

The proxy status endpoint confirms this is an organization egress-policy denial, not a transient failure:

```
$ curl -sS "$HTTPS_PROXY/__agentproxy/status"
  "recentRelayFailures": [ {
      "kind": "connect_rejected",
      "detail": "gateway answered 403 to CONNECT (policy denial or upstream failure)",
      "host": "production.cloudfront.docker.com:443"
  } ]
```

**Blocked host, reported as required: `production.cloudfront.docker.com:443`.**

A registry reachability probe showed which registries the policy *does* permit (`401` on `/v2/` is the normal unauthenticated challenge and means the host is reachable):

```
mirror.gcr.io       -> 401
ghcr.io             -> 401
public.ecr.aws      -> 401
index.docker.io     -> 401
registry-1.docker.io-> 401
quay.io             -> CONNECT tunnel failed, 403
registry.k8s.io     -> CONNECT tunnel failed, 403
```

`mirror.gcr.io` is Google's pull-through mirror of Docker Hub and is permitted, so it was configured as a **daemon-level registry mirror**:

```
$ cat /etc/docker/daemon.json
{ "registry-mirrors": ["https://mirror.gcr.io"] }

$ docker info --format 'mirrors={{.RegistryConfig.Mirrors}}'
mirrors=[https://mirror.gcr.io/]
```

All required base images then pulled successfully:

```
OK    postgres:16-alpine
OK    mongo:7
OK    redis:7-alpine
OK    apache/kafka:3.8.1
OK    openzipkin/zipkin:3
OK    nginx:1.27-alpine
OK    eclipse-temurin:21-jre-alpine
```

**Why this matters for honesty, and what it costs:**

- The mirror is configured **at the daemon level in this sandbox only**. It is deliberately *not* baked into `docker-compose.yml` or the Dockerfiles, which will reference canonical Docker Hub names — the correct thing for a real user on a normal machine.
- Testcontainers talks to the same daemon, so integration tests inherit the mirror automatically with no code change.
- **Honest caveat that will be carried into the final audit:** the §15 question *"Does `docker compose up` reach all-healthy on a fresh machine with no prior images?"* can be answered here only for a machine whose daemon can reach Docker Hub blobs (directly or via a mirror). I can verify the compose topology, health checks, and boot ordering; I cannot verify unmediated Docker Hub connectivity from this sandbox. This limitation will be stated in `docs/final-audit.md` rather than papered over.

### 3.3 Build-tool network access (verified)

```
maven-central: 200   (spring-boot-starter-parent pom fetched)
npm:           200   (registry.npmjs.org/react)
```

Maven Central and the npm registry are reachable. JVM TLS trust is pre-configured via `JAVA_TOOL_OPTIONS` pointing at `/root/.ccr/java-truststore.p12`, confirmed present in the proxy status output (`javaTrustStorePath`).

Latest available versions confirmed against Maven Central metadata:
- Spring Boot: **3.5.16** (latest 3.5.x)
- Spring Cloud: **2025.0.3** (the release train aligned to Boot 3.5.x; 2025.1.x targets Boot 4.0 and will *not* be used)

---

## 4. Gap analysis

The gap is total: **every capability in the master prompt is unimplemented.** Rather than restate that, this section records the decisions that must be made *before* Phase 1 can start, because they determine module boundaries.

| # | Decision | Resolution for this build |
|---|---|---|
| G1 | Java/Spring baseline | Java 21 (LTS, records + sealed types + pattern matching used in the domain), Spring Boot 3.5.16, Spring Cloud 2025.0.3. |
| G2 | Build layout | Single Maven reactor, one root `pom.xml`, modules under `libs/` (5 shared) and `services/` (4 + auth server). Frontend built by `frontend-maven-plugin` under an opt-in profile so `mvn verify` does not require Node for backend-only contributors. |
| G3 | Where the audit chain lives | **PostgreSQL, not MongoDB** — deviation from §2.1, see §6 below. |
| G4 | Image base | `eclipse-temurin:21-jre-alpine` + Spring layered JARs, **not `jlink`** — see §6. |
| G5 | Kafka image | `apache/kafka:3.8.1` (official Apache image, KRaft mode, single broker, no ZooKeeper, no Confluent licensing). |
| G6 | Auth issuer | Self-hosted Spring Authorization Server as a fifth JVM module. Zero external/paid dependencies. |
| G7 | OpenAPI → TS types | springdoc generates the spec; the spec JSON is **committed**, so the frontend build is hermetic. A CI job regenerates and diffs; drift fails the build. |
| G8 | Event schema storage | Versioned JSON Schema files in `libs/contracts/src/main/resources/schemas/<eventType>/v<N>.json`. No schema-registry container. |

---

## 5. Plan

Phases follow §14 exactly. Each ends with a committed `docs/phase-reports/phase-NN.md` containing executed output, and a phase branch merged to `claude/ledgerguard-master-build-d4bpzz`.

| Phase | Deliverable | Acceptance evidence required |
|---|---|---|
| 1 | Maven skeleton, `docs/` tree, all ADRs, all Mermaid diagrams | `mvn -q verify` exits 0 on the empty reactor |
| 2 | Compose profiles, Flyway + Mongo init, `.env.example`, `Makefile` | `docker compose --profile full up -d` → all-healthy `docker compose ps` pasted verbatim |
| 3 | `contracts`, `common-core`, `common-kafka`, `common-observability`, `common-security` | `Money`/ID/schema-compat/ArchUnit tests pass |
| 4 | `transaction-service` write path + outbox | Testcontainers proof of atomic aggregate+outbox, idempotent replay, real Kafka round-trip |
| 5 | `reconciliation-service` saga + rule engine | Saga success/compensation/timeout, per-rule tests, jqwik determinism, PIT score recorded verbatim |
| 6 | `query-service` projections, 360, audit chain | Replay rebuild, duplicate-delivery no-op, tamper detected at correct index |
| 7 | Retry ladder, classification, DLT, replay tooling | Poison→DLT without retry, replay idempotency, Kafka-down degradation observed |
| 8 | Auth server, RBAC, redaction, threat model | Endpoint×role matrix at 100% endpoint coverage, passing |
| 9 | Tracing over Kafka, metrics, structured logs | One `traceId` across 4 services + 2 broker hops, asserted by test |
| 10 | Frontend, 10 screens | 0 TS errors, 0 ESLint errors, screens render against the live stack |
| 11 | Full test suite | Whole suite green, nothing skipped/disabled; real counts recorded |
| 12 | k6 scenarios | Results artifact committed with this machine's specs |
| 13 | CI/CD, scanners, image hardening | A real workflow run observed |
| 14 | Demo scripts, screenshots, README | Every README command executed verbatim from a clean clone |
| 15 | Adversarial audit | `docs/final-audit.md` with evidence per finding |

**Sequencing risk that will be managed, not hidden:** phases 4–11 are where a project like this usually degrades into stubs. Per §0.7 and §16, depth wins over breadth: if a capability cannot be finished honestly, a smaller working version ships and the gap is written into the phase report and the README's limitations section rather than faked.

---

## 6. Requirements I judge infeasible or unwise, with reasoning

§16 asks for engineering judgement to be stated, not exercised silently. These are the deviations I intend to make. Each will be restated in the phase report where it lands and, where architectural, in an ADR.

### 6.1 The audit chain belongs in PostgreSQL, not MongoDB — the prompt contradicts itself

§2.1 places the audit archive in `query-service`/MongoDB. §6 requires that the audit table have **no `UPDATE`/`DELETE` grants for the application role, enforced by a database grant in migration**, and requires a strictly sequential `previousHash` chain.

Those two requirements fight each other. A hash chain needs a **single writer with a serializable append** — every record must observe the immediately preceding record's hash. PostgreSQL gives that with a sequence, a unique constraint on the chain index, and a real `REVOKE UPDATE, DELETE` in a migration. MongoDB can approximate it (unique index + transaction + a role limited to `find`/`insert`), but the enforcement is weaker and the ordering guarantee is something I would have to hand-build.

**Decision:** the authoritative hash-chained audit log lives in PostgreSQL under a schema owned by `query-service`; MongoDB keeps the read projections (Transaction 360, grid). `query-service` therefore touches three stores. That is a real cost and the ADR will say so plainly: *we chose provable chain integrity over datastore minimalism.*

### 6.2 `jlink` runtime images are cost without benefit here

§11 offers "`jlink` **or** JRE base + layered JARs". I will take the second. `jlink` adds a custom-runtime build stage, per-module dependency analysis that breaks on reflection-heavy Spring code, and CI time, to save perhaps 60–80 MB per image. The §0.1 constraint that actually binds is RAM, not image size. Images will still be multi-stage, non-root, digest-pinned, `HEALTHCHECK`-defined, with no build tooling in the runtime layer, and their sizes recorded.

### 6.3 Grepping the frontend for "suspicious fixture arrays" is security theatre

§8.1 asks for a CI grep of `src/` for fabricated data. A regex for "looks like fake data" has a high false-positive rate, is trivially defeated by anyone who wants to defeat it, and — worse — creates the *appearance* of an enforced guarantee.

**Decision:** implement the guarantee structurally *and* keep a narrow grep as a labelled backstop:
1. Fixtures and MSW handlers may exist only under `src/**/__tests__/` and `src/test/`; ESLint `no-restricted-imports` forbids app code from importing them, so a violation fails the build at lint time rather than at grep time.
2. Every API response is parsed through a Zod schema at the boundary, so a hand-written array cannot satisfy the typed query layer without also faking a network call.
3. A narrow grep remains, documented as *a backstop, not a proof*.

### 6.4 A "soak" test on 4 shared cores measures the sandbox, not the system

§10 requires k6 smoke/ramp/steady/spike/soak. They will be written and executed, but on this machine k6 competes for the same 4 cores as the entire stack, so the numbers describe *this contended sandbox*. They will be committed with the hardware block attached and an explicit statement that they must not be read as capacity figures. Per §10 I will not write a number I did not produce, and I will not extrapolate one I did.

### 6.5 "CI green on a real run" is conditional on the repository's Actions being enabled

I can push and read run results through the GitHub API. If Actions are disabled on the repository or no runner minutes are available, Phase 13's acceptance becomes unverifiable from here. If that happens it will be reported as *unverified*, with the workflow files still committed and locally-runnable equivalents (`make verify`) demonstrated instead.

### 6.6 Two things in the prompt I judge correct and want to record agreement on

- **No AI/ML anywhere in the reconciliation decision path.** The prompt offers an optional "suggest-only" assist. I will omit it entirely. It adds a dependency, a failure mode, and a reviewer question, and buys nothing a deterministic rule cascade does not already do better.
- **Hand-rolling the saga orchestrator instead of adopting Temporal/Camunda/Axon.** Correct for a portfolio system whose point is to demonstrate the mechanism. The production alternative will be argued in the ADR rather than adopted.

---

## 7. Acceptance criteria for Phase 0

| Criterion (from §14) | Status | Evidence |
|---|---|---|
| Existing repository contents enumerated; nothing useful deleted | **Met** | §1 — `git ls-files` output; all three files preserved |
| Toolchain verified by executed version commands | **Met** | §2 — real output from `java`, `mvn`, `docker`, `docker compose`, `node`, `npm` |
| Docker usable | **Met (with intervention)** | §3.1, §3.2 — daemon started; egress-blocked host reported; permitted mirror configured; 7/7 required images pulled |
| Gap analysis produced | **Met** | §4 |
| Concrete plan produced | **Met** | §5 |
| Explicit list of infeasible/unwise requirements with reasoning | **Met** | §6 — six items |
| Plan committed | **Met** | this file, committed on `claude/ledgerguard-master-build-d4bpzz` |

---

## 8. Known gaps carried into Phase 1

1. `.gitignore` is still the stock Java template — extend it before the first source commit so `target/` and `node_modules/` never enter history.
2. No `.env.example` exists yet; no secret-scanning is active yet. Both land in Phase 2 / Phase 13. Until then, **no credential of any kind may be written to a file** — the compose stack will use placeholder values sourced from `.env.example` only.
3. The Docker daemon start and registry mirror are session-local. If this session's container is recycled, both must be re-applied before any Testcontainers or compose step.
4. Disk headroom is 28 GB and the `full` profile plus Maven and npm caches will consume a meaningful share. Disk will be checked before each container-heavy phase.

## 9. Entry conditions for Phase 1

- [x] JDK 21 + Maven 3.9 on `PATH`, Maven Central reachable
- [x] Docker daemon running, images pullable
- [x] Baseline decisions G1–G8 fixed
- [x] Phase 0 report committed
