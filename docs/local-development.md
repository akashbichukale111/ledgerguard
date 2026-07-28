# Local development

> **Status.** The toolchain requirements and the sandbox notes below are real as of Phase 1. The
> compose and `make` workflow is added in Phase 2 — commands referencing it are marked
> *(Phase 2)* and do not work yet. This document never lists a command that has not been executed.

## Required toolchain

Verified working versions (from `docs/phase-reports/phase-00.md` §2):

| Tool | Version used | Minimum |
|---|---|---|
| JDK | 21.0.10 (Temurin/OpenJDK) | 21 |
| Maven | 3.9.11 | 3.9 |
| Docker Engine | 29.3.1 | 24 |
| Docker Compose | v5.1.1 | v2 |
| Node.js | 22.22.2 | 20 |
| npm | 10.9.7 | 10 |

The Maven build enforces the JDK and Maven floors via `maven-enforcer-plugin`, so a wrong version
fails immediately with a clear message rather than at a confusing later step.

## Building

```bash
# Full reactor: compile, unit tests, integration tests, formatting check
mvn verify

# Skip integration tests for a fast inner loop (CI must never do this)
mvn verify -DskipITs

# Fix formatting violations
mvn spotless:apply
```

Formatting is **enforced, not suggested** — `spotless:check` runs in the `verify` phase and fails
the build. If a build fails on formatting, run `mvn spotless:apply` and commit the result.

## Module layout

```
libs/                       shared libraries — no ports, no datastores
  contracts/                event envelope + versioned JSON Schemas
  common-core/              Money, identifiers, Clock, errors — no Spring, no JPA
  common-kafka/             envelope serde, idempotent consumers, retry/DLT topology
  common-observability/     tracing, MDC, logging and metric conventions
  common-security/          JWT config, RBAC annotations, redaction
services/                   deployable services
  ledgerguard-gateway/      edge: routing, auth, rate limiting
  transaction-service/      write side + transactional outbox
  reconciliation-service/   saga orchestration + matching engine
  query-service/            projections, 360, audit chain, STOMP
  auth-server/              local OAuth2 issuer
```

Each module's `package-info.java` states its responsibility and its constraints. Boundaries are
enforced by ArchUnit, not by convention ([ADR-0013](adr/0013-hexagonal-layering.md)).

## Running the stack *(Phase 2)*

Three profiles exist so the stack can be run at three different costs:

| Profile | Contents | Use when |
|---|---|---|
| `infra` | Datastores, Kafka, Zipkin | Running services from an IDE |
| `core` | `infra` + auth, transaction, reconciliation, gateway | Demoing the write path and reconciliation |
| `full` | Everything, including the console | Full demo |

*Commands and measured RAM cost per profile are added in Phase 2, from `docker stats` output on
the reference machine. Nothing is estimated here.*

---

## Sandbox-only notes

**These apply to the Anthropic cloud session this repository was built in. They are not steps a
normal user needs, and nothing in the committed configuration depends on them.**

### The Docker daemon may not be running

The `docker` CLI is installed but no daemon is started. As root:

```bash
nohup dockerd > /var/log/dockerd.log 2>&1 &
docker info --format '{{.ServerVersion}}'
```

This must be repeated if the session container is recycled, before any Testcontainers or compose
step.

### Docker Hub's blob CDN is blocked by egress policy

Image manifests resolve but blob downloads from `production.cloudfront.docker.com` return `403`.
A permitted pull-through mirror is configured at the daemon level:

```bash
cat > /etc/docker/daemon.json <<'JSON'
{ "registry-mirrors": ["https://mirror.gcr.io"] }
JSON
# restart dockerd, then verify:
docker info --format 'mirrors={{.RegistryConfig.Mirrors}}'
```

**Deliberately not baked into the repository.** `docker-compose.yml` and the Dockerfiles reference
canonical Docker Hub image names, which is correct for a real user on a normal machine.
Testcontainers talks to the same daemon and inherits the mirror with no code change.

The consequence for verification honesty: this environment can prove the compose topology, health
checks, and boot ordering are correct. It **cannot** prove that image pulls work from a machine
with unmediated Docker Hub access. That limitation is carried into `docs/final-audit.md` rather
than glossed over.
