# Architecture

> **Status of this document.** The diagrams and the rejected-pattern reasoning are complete as of
> Phase 1. Sections marked *(implemented in Phase N)* describe intent that the code does not yet
> fulfil. Every such marker is removed only when the corresponding code exists and is tested —
> a diagram that does not match the implementation is a defect, and the Phase 15 audit checks
> exactly this.

## 1. System context (C4 level 1)

```mermaid
C4Context
  title LedgerGuard — system context

  Person(analyst, "Reconciliation Analyst", "Investigates breaks, resolves exceptions with justification")
  Person(ops, "Operations Engineer", "Replays dead letters, retries sagas, watches system health")
  Person(auditor, "Auditor", "Read-only. Inspects the audit trail and verifies chain integrity")

  System(ledgerguard, "LedgerGuard", "Ingests transactions, reconciles internal ledger entries against external statements, raises and tracks breaks, records a tamper-evident audit trail")

  System_Ext(core_banking, "Internal Ledger / Core Banking", "Source of internal ledger entries")
  System_Ext(counterparty, "Counterparty Systems", "Banks, PSPs, card networks, custodians. Source of external statement entries")
  System_Ext(zipkin, "Zipkin", "Distributed trace storage and query")

  Rel(analyst, ledgerguard, "Investigates and resolves breaks", "HTTPS / WSS")
  Rel(ops, ledgerguard, "Operates", "HTTPS")
  Rel(auditor, ledgerguard, "Reads audit trail", "HTTPS")
  Rel(core_banking, ledgerguard, "Submits transactions and ledger entries", "HTTPS / JSON")
  Rel(counterparty, ledgerguard, "Submits statement entries", "HTTPS / JSON")
  Rel(ledgerguard, zipkin, "Exports spans", "HTTP")
```

**Scope note.** Counterparty and core-banking systems are *external actors* in this diagram. In the
local stack they are simulated by the demo scripts in `scripts/demo/` posting to the same public
API — there is no separate simulator service, and no file-transfer or SWIFT ingestion channel.
That is a deliberate scope limit, not an omission to be discovered later.

## 2. Container diagram (C4 level 2)

```mermaid
C4Container
  title LedgerGuard — containers

  Person(user, "Analyst / Operator / Auditor")

  Container_Boundary(lg, "LedgerGuard") {
    Container(spa, "Operations Console", "React 18, TypeScript, Vite, nginx", "Ten screens. Every value from a real API or WebSocket message")
    Container(gw, "ledgerguard-gateway", "Spring Cloud Gateway", "Routing, JWT validation, rate limiting, correlation-ID minting, security headers. No business logic")
    Container(auth, "auth-server", "Spring Authorization Server", "Local OAuth2 issuer. Signs the JWTs every service validates")
    Container(txn, "transaction-service", "Spring Boot", "Write side: aggregates, idempotency ledger, transactional outbox")
    Container(recon, "reconciliation-service", "Spring Boot", "Saga orchestration, matching engine, exception lifecycle")
    Container(query, "query-service", "Spring Boot", "Projections, Transaction 360, audit chain, STOMP fan-out")

    ContainerDb(pg, "PostgreSQL", "PostgreSQL 16", "System of record, outbox, saga state, audit chain")
    ContainerDb(mongo, "MongoDB", "MongoDB 7", "Read-model projections")
    ContainerDb(redis, "Redis", "Redis 7", "Rate-limit buckets, aggregate cache, idempotency fast path")
    ContainerQueue(kafka, "Kafka", "Kafka 3.8 KRaft", "Durable, partitioned, replayable event log")
  }

  System_Ext(zipkin, "Zipkin")

  Rel(user, spa, "Uses", "HTTPS")
  Rel(spa, gw, "REST + STOMP over WebSocket", "HTTPS / WSS")
  Rel(gw, auth, "Fetches JWKS", "HTTP")
  Rel(gw, txn, "Routes commands", "HTTP")
  Rel(gw, query, "Routes queries, proxies WebSocket upgrade", "HTTP / WS")
  Rel(gw, redis, "Rate-limit buckets", "RESP")

  Rel(txn, pg, "Aggregate + outbox in ONE transaction", "JDBC")
  Rel(txn, kafka, "Outbox poller publishes", "Kafka protocol")
  Rel(recon, kafka, "Consumes and produces", "Kafka protocol")
  Rel(recon, pg, "Saga state, case event stream", "JDBC")
  Rel(query, kafka, "Consumes for projection", "Kafka protocol")
  Rel(query, mongo, "Projections", "Mongo wire")
  Rel(query, pg, "Hash-chained audit log", "JDBC")
  Rel(query, redis, "Aggregate cache", "RESP")

  Rel(txn, zipkin, "Spans", "HTTP")
  Rel(recon, zipkin, "Spans", "HTTP")
  Rel(query, zipkin, "Spans", "HTTP")
```

Why each service is a separate deployable — and why there are four rather than one or twelve — is
argued in [ADR-0001](adr/0001-service-decomposition.md). Why `query-service` touches three
datastores is [ADR-0014](adr/0014-audit-chain-in-postgresql.md), which is a documented deviation
from the specification.

## 3. Transaction ingestion — the write path

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant GW as gateway
    participant TS as transaction-service
    participant PG as PostgreSQL
    participant OP as Outbox poller
    participant K as Kafka

    C->>GW: POST /api/v1/transactions<br/>Idempotency-Key, Bearer JWT
    GW->>GW: Validate JWT, mint correlationId if absent,<br/>rate-limit by principal
    GW->>TS: Forward + X-Correlation-Id + traceparent

    TS->>PG: SELECT idempotency_record WHERE key = ?
    alt Key seen, body hash matches
        PG-->>TS: stored response
        TS-->>C: 200 + Idempotent-Replay: true
    else Key seen, body hash DIFFERS
        TS-->>C: 409 Problem Details (conflicting reuse)
    else New key
        rect rgb(235, 245, 255)
        note over TS,PG: ONE local ACID transaction
        TS->>PG: INSERT transaction aggregate
        TS->>PG: INSERT ledger_entry rows
        TS->>PG: INSERT idempotency_record (UNIQUE key)
        TS->>PG: INSERT outbox_record (TransactionReceived)
        PG-->>TS: COMMIT
        end
        TS-->>C: 202 Accepted + transactionId
    end

    note over OP,K: Asynchronous, decoupled from the request
    OP->>PG: SELECT ... FOR UPDATE SKIP LOCKED
    OP->>K: publish(key = aggregateId)
    K-->>OP: ack
    OP->>PG: UPDATE outbox SET published_at = now()
```

**The failure window is deliberate and visible in this diagram.** If the poller crashes between the
Kafka ack and the `UPDATE`, the record is re-claimed and republished on restart — the event is
delivered twice. That is at-least-once delivery, and it is why every consumer is idempotent
([ADR-0006](adr/0006-at-least-once-and-idempotency.md)). It is not a bug to be fixed; it is the
guarantee this system actually provides.

## 4. CQRS — and where consistency stops being immediate

```mermaid
flowchart LR
    subgraph WRITE["Write side — strongly consistent"]
        direction TB
        CMD[Command API] --> AGG[Aggregate<br/>optimistic locking]
        AGG --> PGW[(PostgreSQL<br/>system of record)]
        AGG -.same transaction.-> OBX[(outbox)]
    end

    OBX --> POLL[Outbox poller]
    POLL --> KAFKA{{Kafka<br/>partitioned by aggregateId}}

    subgraph READ["Read side — EVENTUALLY consistent"]
        direction TB
        CONS[Idempotent projection consumer] --> MONGO[(MongoDB<br/>projections)]
        MONGO --> QAPI[Query API / Transaction 360]
    end

    KAFKA --> CONS
    QAPI --> UI[Operations Console]

    KAFKA -.-> AUDIT[Audit consumer]
    AUDIT --> PGA[(PostgreSQL<br/>hash-chained audit)]

    style WRITE fill:#e8f4ff,stroke:#2563eb
    style READ fill:#fff4e6,stroke:#d97706
    style KAFKA fill:#f3e8ff,stroke:#7c3aed
```

**The orange boundary is the honest part of this system.** A write that returns `202` is durable in
PostgreSQL but is **not yet visible** in the read model. The delay is the outbox poll interval plus
Kafka delivery plus projection time.

The console does not hide this. Projection lag — measured as event `occurredAt` to projection
`updatedAt` — is surfaced in the UI header and exported as a metric. A CQRS system that pretends
its read model is current is lying to its operators at exactly the moment they most need the truth.

## 5. Kafka topology, retries, and dead letters

```mermaid
flowchart TD
    subgraph SRC["Source topics"]
        T1[transactions.events.v1]
        T2[reconciliation.events.v1]
        T3[saga.commands.v1]
        T4[audit.events.v1]
    end

    T1 --> CONS[Consumer]
    CONS --> OK{Processed?}
    OK -->|success| DONE[Commit offset]

    OK -->|failure| CLASS{Classify error}
    CLASS -->|NON-RETRYABLE<br/>deserialization, schema violation,<br/>validation, business rejection| DLT[(topic.dlt)]
    CLASS -->|RETRYABLE<br/>transient DB / IO / timeout| R1[(topic.retry.1 — 5s)]

    R1 --> C1[Consumer] --> Q1{ok?}
    Q1 -->|yes| DONE
    Q1 -->|no| R2[(topic.retry.2 — 30s)]

    R2 --> C2[Consumer] --> Q2{ok?}
    Q2 -->|yes| DONE
    Q2 -->|no| R3[(topic.retry.3 — 5m)]

    R3 --> C3[Consumer] --> Q3{ok?}
    Q3 -->|yes| DONE
    Q3 -->|no| DLT

    DLT --> OPS[DLQ Operations screen<br/>role: OPERATIONS]
    OPS -->|replay, new causationId,<br/>audited, idempotent| T1

    style DLT fill:#fee2e2,stroke:#dc2626
    style CLASS fill:#fef3c7,stroke:#d97706
```

The classification step is the substantive part. A malformed payload will fail identically in 5
seconds, 30 seconds, and 5 minutes — sending it round the ladder wastes the retry budget and
**delays the operator's discovery of the problem by five and a half minutes**. Reasoning in
[ADR-0012](adr/0012-non-blocking-retry-topics.md).

Retry delays carry jitter so that a recovering dependency is not hit by every retry at once.

## 6. Local deployment topology

```mermaid
flowchart TB
    subgraph HOST["Developer laptop — docker compose"]
        subgraph INFRA["profile: infra"]
            PG[(postgres:16-alpine)]
            MG[(mongo:7)]
            RD[(redis:7-alpine)]
            KF{{apache/kafka:3.8.1<br/>KRaft, single broker}}
            ZP[openzipkin/zipkin:3]
        end
        subgraph CORE["profile: core — adds"]
            AU[auth-server]
            TXS[transaction-service]
            RCS[reconciliation-service]
            GWS[ledgerguard-gateway]
        end
        subgraph FULL["profile: full — adds"]
            QRS[query-service]
            WEB[console — nginx]
        end
    end

    BROWSER([Browser]) --> WEB
    WEB --> GWS
    GWS --> TXS & QRS & AU
    GWS --> RD
    TXS --> PG & KF
    RCS --> PG & KF
    QRS --> MG & PG & RD & KF
    TXS & RCS & QRS --> ZP

    style INFRA fill:#eef2ff,stroke:#4f46e5
    style CORE fill:#ecfdf5,stroke:#059669
    style FULL fill:#fff7ed,stroke:#ea580c
```

Profiles exist so the stack can be run at three different costs:

| Profile | Contents | Purpose |
|---|---|---|
| `infra` | Datastores + Kafka + Zipkin only | Run services from an IDE |
| `core` | `infra` + auth, transaction, reconciliation, gateway | Minimum viable demo of the write path and reconciliation |
| `full` | Everything, including the console | Complete demo |

*Measured RAM cost per profile is recorded in Phase 2's report, from `docker stats` on the
reference machine — it is deliberately not estimated here.*

## 7. Patterns and technologies deliberately rejected

Every entry below was considered and rejected. Listing them is as much a part of the architecture
as the things that were adopted, because "what did you decide **not** to build" is the question
that separates a designed system from an accumulated one.

| Rejected | Why |
|---|---|
| **Service mesh** (Istio/Linkerd) | Solves mTLS, retries, and traffic shifting across many services and teams. With four services in one compose file, it adds a control plane and sidecar-per-pod memory cost to solve problems we do not have. Retries and timeouts are handled in-process where they are visible in code. |
| **Kubernetes as the primary deployment story** | The target is a laptop. K8s manifests would be untested aspiration — nobody would run them, so they would rot. Docker Compose is what this project can actually verify, so it is what it ships. |
| **Confluent Schema Registry** | Another container to enforce what CI can enforce at build time. See [ADR-0007](adr/0007-schema-in-repo.md). |
| **Distributed XA transactions** | Two-phase commit across PostgreSQL and Kafka: poor availability (a coordinator failure blocks participants holding locks), poor performance, and poor support. The saga plus outbox exists precisely because XA is the wrong answer. |
| **gRPC between services** | Services communicate by **events over Kafka**, not by synchronous calls. Adding gRPC would mean adding synchronous coupling the architecture deliberately avoids. |
| **GraphQL** | The console's queries are well-known and server-shaped. GraphQL's flexibility buys nothing when there is exactly one client, and it complicates authorization (per-field rules), caching, and rate limiting — all of which matter here. |
| **A second message broker** (RabbitMQ alongside Kafka) | Kafka's log semantics are required for replay and projection rebuild. Nothing here needs per-message routing or acknowledgement semantics that Kafka lacks. |
| **Elasticsearch** | Grid filters are structured, not full-text. Compound indexes serve them. See [ADR-0002](adr/0002-polyglot-persistence.md). |
| **A rules DSL interpreter** | A DSL means writing a parser, an evaluator, error reporting, and tooling — then debugging *the DSL* instead of the rules. The rules are versioned configuration evaluated by ordinary, individually-testable Java. Explainability comes from structured output, not from grammar. |
| **Any ML/LLM in the reconciliation decision path** | Financial correctness must be deterministic, reproducible, and explainable. A model that is 99% accurate is 1% unexplainable, and "the model decided" is not an answer to an auditor. See `docs/reconciliation-engine.md`. |
| **Workflow engine** (Temporal/Camunda/Axon) | The mechanism is the deliverable. See [ADR-0004](adr/0004-orchestrated-saga-hand-rolled.md), which also states when Temporal *would* be right. |

## 8. Resource budget

Target from the build specification: full stack under **10 GB RAM** and **6 CPU cores**, cold start
to healthy under **5 minutes**.

The reference machine for this repository has **4 cores**, not 6 — recorded in
`docs/phase-reports/phase-00.md` §2. Cold-start timings will therefore be slower than the target
assumes. Actual measurements land in Phase 2's report; no number is claimed here in advance.

## Related documents

- [Architecture Decision Records](adr/README.md) — the reasoning behind every choice above
- [`domain-model.md`](domain-model.md) — entities, state machines, invariants
- [`reconciliation-engine.md`](reconciliation-engine.md) — the matching pipeline
- [`saga.md`](saga.md) — orchestration and compensation
- [`reliability.md`](reliability.md) — what is and is not guaranteed
- [`failure-modes.md`](failure-modes.md) — the operational runbook
