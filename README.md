# LedgerGuard

**Real-time financial reconciliation and audit platform**

[![Build and Test](https://github.com/akashbichukale111/ledgerguard/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/akashbichukale111/ledgerguard/actions/workflows/build-and-test.yml)
[![Security Scan](https://github.com/akashbichukale111/ledgerguard/actions/workflows/security-scan.yml/badge.svg)](https://github.com/akashbichukale111/ledgerguard/actions/workflows/security-scan.yml)

## Overview

LedgerGuard is a cloud-native, event-driven platform for real-time transaction reconciliation across multiple financial systems. It processes transaction streams, detects matches, routes errors to a dead-letter topic, and provides operators with an audit trail and replay capability.

**Key Capabilities**:
- **Transaction Ingestion**: Accept transactions from multiple sources via REST API or Kafka
- **Real-Time Reconciliation**: Match incoming transactions using configurable business rules
- **Error Handling**: Retry failed messages with exponential backoff; route unrecoverable errors to DLT
- **CQRS Projections**: Maintain read-optimized views of transaction state and reconciliation status
- **Audit Trail**: Complete event history with cryptographic proof of integrity
- **DLT Explorer**: Browse and replay dead-lettered messages from the UI
- **Distributed Tracing**: W3C traceparent headers across Kafka and HTTP for end-to-end visibility
- **Role-Based Access Control**: Enforce permissions at REST layer with RBAC matrix
- **PII Redaction**: Automatically redact sensitive fields in logs and audit trails

## Quick Start

### Prerequisites

- **JDK 21**: OpenJDK Temurin or compatible
- **Maven 3.8+**: Dependency management and build tool
- **Docker & Docker Compose**: Containerization and local development
- **Node.js 20+**: Frontend build (optional if using pre-built frontend)
- **k6**: Performance testing (optional)

### 5-Minute Setup

```bash
# 1. Clone and navigate to project
git clone https://github.com/akashbichukale111/ledgerguard.git
cd ledgerguard

# 2. Start infrastructure (Postgres, Kafka, Prometheus, Grafana)
docker-compose -f docker-compose-full.yml up -d

# 3. Build all services (unit tests only; ~2 min with cache)
mvn clean package -DskipITs

# 4. Start backend services (3 separate terminals)
# Terminal 1: Transaction Service
java -jar services/transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar

# Terminal 2: Reconciliation Service
java -jar services/reconciliation-service/target/reconciliation-service-0.1.0-SNAPSHOT.jar

# Terminal 3: Query Service
java -jar services/query-service/target/query-service-0.1.0-SNAPSHOT.jar

# 5. Start frontend (Terminal 4)
cd frontend && npm ci && npm run dev

# 6. Access UI
# Frontend: http://localhost:3000
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3001 (admin/admin)
```

### Using Docker Compose (Recommended for First Run)

```bash
# Build all images and start everything
docker-compose -f docker-compose-full.yml up --build

# Wait ~30 seconds for services to start

# Access:
# - Frontend: http://localhost:3000
# - API: http://localhost:8080/api/v1 (proxied through frontend)
# - Prometheus: http://localhost:9090
# - Grafana: http://localhost:3001

# Shutdown
docker-compose -f docker-compose-full.yml down
```

## Architecture

### System Diagram

```
┌─────────────────┐
│  Transaction    │
│    Sources      │
└────────┬────────┘
         │ REST API
         ▼
┌─────────────────────────────────────────┐
│     Transaction Service                 │
│  ┌──────────────┐  ┌──────────────┐   │
│  │ Ingestion    │  │ Outbox       │   │
│  │ REST API     │  │ (Dedup)      │   │
│  └──────┬───────┘  └──────┬───────┘   │
│         │                 │            │
│         └─────────────────┘            │
│               │                         │
│  transactions.events.v1 (Kafka topic)  │
└─────────────────────────────────────────┘
         │
         │ Kafka Stream
         ▼
┌─────────────────────────────────────────┐
│  Reconciliation Service                 │
│  ┌──────────────────────────────────┐  │
│  │ Matching Engine                   │  │
│  │ - Fuzzy match (amount ±1%)       │  │
│  │ - Deadline filtering             │  │
│  │ - Transaction priority ranking   │  │
│  └──────────────────────────────────┘  │
│         │                 │              │
│    MATCHED         UNMATCHED            │
│         │                 │              │
│ matched.txn.v1   unmatched.dlt.v1      │
└─────────────────────────────────────────┘
         │                 │
         └────────┬────────┘
                  ▼
         ┌────────────────┐
         │ Kafka Topics   │
         │ (Event Store)  │
         └────────────────┘
                  │
         ┌────────┴────────┐
         │                 │
         ▼                 ▼
    ┌─────────────┐  ┌──────────────┐
    │Query Service│  │Error Handler │
    │(Projections)│  │(Retry Ladder)│
    └─────────────┘  └──────────────┘
         │                 │
    Read Models      DLT Management
         │                 │
    ┌────────────────────────────┐
    │ PostgreSQL Projections      │
    │ - Transaction 360 view      │
    │ - Reconciliation status     │
    │ - Audit trail               │
    │ - DLT message store         │
    └────────────────────────────┘
         │
         ▼
    ┌─────────────────────────────┐
    │  Operations Console (React) │
    │  - Dashboard                │
    │  - Transaction search       │
    │  - DLT Explorer + Replay    │
    │  - Audit trail timeline     │
    └─────────────────────────────┘
```

### Service Breakdown

#### Transaction Service
- **Responsibility**: Accept and persist incoming transactions
- **Technology**: Spring Boot, PostgreSQL, Kafka
- **Port**: 8081 (Docker) / 8080 (direct)
- **Key Endpoint**: `POST /api/v1/transactions/ingest`

#### Reconciliation Service
- **Responsibility**: Match transactions using business rules
- **Technology**: Spring Boot, Kafka Streams, Saga pattern
- **Port**: 8082 (Docker) / 8080 (direct)
- **Algorithms**: Fuzzy matching, priority ranking, deadline filtering

#### Query Service
- **Responsibility**: CQRS projections and audit trail
- **Technology**: Spring Boot, PostgreSQL (projections), Kafka consumer
- **Port**: 8083 (Docker) / 8080 (direct)
- **Key Endpoints**: Transaction 360 view, DLT explorer, metrics

#### Frontend
- **Responsibility**: Operations console UI
- **Technology**: React 18, TypeScript, Vite
- **Port**: 3000 (dev) / 80 (Docker)
- **Key Pages**: Dashboard, transaction search, DLT explorer, audit trail

### Event Flow

```
1. Transaction Ingestion
   Client → Transaction Service REST API
   ↓
   Store in PostgreSQL
   ↓
   Publish to Kafka: transactions.events.v1

2. Reconciliation
   Kafka Consumer (Reconciliation Service)
   ↓
   Matching Engine (fuzzy match, priority rank)
   ↓
   MATCHED? → matched.txn.v1 (topic)
   UNMATCHED? → unmatched.dlt.v1 (topic)

3. Error Handling (Retry Ladder)
   Processing Failure?
   ↓
   Publish to retry.1 (1st attempt)
   ↓
   Retry Consumer reprocesses
   ↓
   Still Fails? → retry.2 (2nd attempt)
   ↓
   Still Fails? → retry.3 (3rd attempt)
   ↓
   Still Fails? → dlt (dead-letter topic)

4. Audit Trail
   All events published to Kafka → PostgreSQL projection
   ↓
   Audit entries stored with correlation IDs
   ↓
   Accessible in UI audit trail page

5. Query Service Projections
   Subscribe to all topics
   ↓
   Maintain read models in PostgreSQL
   ↓
   Support fast queries: transaction 360 view, DLT list, metrics
```

## Development

### Project Structure

```
ledgerguard/
├── .github/
│   └── workflows/              GitHub Actions CI/CD (build, test, security, Docker)
├── libs/
│   ├── common-core/            Shared domain models (Transaction, Money, UUID)
│   ├── common-kafka/           Kafka infrastructure (producer/consumer config)
│   ├── common-security/        RBAC matrix, role hierarchy, PII redaction
│   ├── common-observability/   W3C traceparent, MDC context, metrics
│   └── contracts/              Event schema definitions (ProtoBuf)
├── services/
│   ├── transaction-service/    Ingestion and persistence
│   ├── reconciliation-service/ Matching engine and saga
│   └── query-service/          Projections, DLT, audit trail
├── frontend/                   React SPA operations console
├── docs/
│   ├── adr/                    Architecture Decision Records
│   ├── phase-reports/          Phase-by-phase implementation details
│   └── diagrams/               Architecture and data flow diagrams
├── perf/                       k6 load testing scenarios
└── infra/
    ├── migrations/             Flyway database migrations
    └── docker-compose-*.yml    Service composition for development
```

### Building

```bash
# Build all modules (runs unit tests)
mvn clean package -DskipITs

# Build specific service
mvn clean package -pl services/transaction-service

# Build without tests
mvn clean package -DskipTests

# Run only unit tests
mvn clean test -DskipITs

# Run integration tests (requires Docker)
mvn clean test
```

### Running Tests

```bash
# Unit tests (fast, ~30s)
mvn clean test -DskipITs

# Integration tests (slower, ~5 min, requires Docker)
mvn clean test

# Specific test class
mvn test -Dtest=RbacMatrixTest

# Code quality checks
mvn spotless:check              # Code formatting
mvn spotbugs:check              # Static analysis
mvn org.owasp:dependency-check-maven:check  # Vulnerability scan
```

### Code Quality

All code must pass:
- **Spotless**: Code formatting (Maven spotless plugin)
- **SpotBugs**: Static analysis (common bugs)
- **Architecture Tests**: ArchUnit layer validation
- **Unit Tests**: All modules have unit tests

Auto-format code:
```bash
mvn spotless:apply
```

### Running Frontend Locally

```bash
cd frontend

# Development mode (Vite dev server with HMR)
npm run dev          # http://localhost:3000

# Type check
npm run type-check

# Production build
npm run build        # Output: dist/

# Preview production build
npm run preview
```

## Testing

### Test Suite Overview

**215 unit tests, 0 failures** — the figure `mvn verify -DskipITs` prints today. Includes jqwik
property tests over the reconciliation engine, an ArchUnit layer check, and Kafka event schema
contract tests.

**4 integration tests** (`*IT`, Testcontainers) exist and compile: `WritePathIT`,
`SagaOrchestratorIT`, `ProjectionIT`, `AuditChainIT`. They require a Docker daemon and **have not
yet been executed** — their status is unknown, not passing. The first CI run on a Docker-capable
runner is their real gate.

`transaction-service` currently has no unit tests. See
[phase-16](docs/phase-reports/phase-16-audit-and-remediation.md) for the full gap list.

### Running Performance Tests

```bash
cd perf

# Quick test (5 min, 50 VUs)
make test-transaction BASE_URL=http://localhost:8080/api/v1

# All tests (90 min total)
make test-all

# Specific scenario
make test-spike       # 6× surge to 300 VUs
make test-soak        # 100 VUs × 30 min
```

## Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for production deployment guidelines including:
- Docker image building and registry push
- Kubernetes manifests
- Database migration strategy
- Health checks and monitoring
- Scaling considerations

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines including:
- Branch naming conventions
- Commit message format
- Pull request process
- Code review checklist
- Setting up development environment

## API Reference

### Transaction Ingestion

```bash
POST /api/v1/transactions/ingest

{
  "transactionId": "tx-12345",
  "amount": "1234.56",
  "currency": "USD",
  "counterparty": "Bank of America",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### Transaction Search

```bash
GET /api/v1/transactions/search?query=12345&limit=10

Response: [{
  "transactionId": "tx-12345",
  "status": "MATCHED",
  "amount": "1234.56",
  "currency": "USD",
  "timestamp": "2024-01-15T10:30:00Z",
  "correlationId": "corr-xyz"
}]
```

### DLT Replay

```bash
POST /api/v1/replay/dlt-message

{
  "originalEnvelope": "{...}"
}

Response: {
  "causationId": "replay-uuid",
  "message": "{...}"
}
```

### Metrics

```bash
GET /api/v1/metrics/dashboard

Response: {
  "projectionLag": 234,      # milliseconds
  "dltDepth": 42,            # message count
  "consumerLag": 5,          # partition lag
  "transactionRate": 125.5,  # txn/sec
  "matchRate": 99.2,         # percentage
  "errorRate": 0.3           # percentage
}
```

## Observability

### Metrics (Prometheus)

```
http://localhost:9090

Key metrics:
- ledgerguard_transaction_ingest_count{status}
- ledgerguard_matching_duration_ms{outcome}
- ledgerguard_projection_lag_ms
- ledgerguard_dlt_depth_count
```

### Tracing

- **W3C Traceparent**: Kafka headers propagate trace context
- **Correlation ID**: Links related events across services
- **Zipkin Integration** (future): OpenTelemetry export

View trace logs:

```bash
# All events for a transaction
grep "corr-xyz" logs/app.log

# Filter by trace ID
grep "trace-abc123" logs/app.log
```

### Audit Trail

```bash
GET /api/v1/audit/entries?correlationId=corr-xyz

Response: [{
  "timestamp": "2024-01-15T10:30:00Z",
  "actor": "admin",
  "action": "TRANSACTION_INGESTED",
  "aggregateId": "tx-12345",
  "details": "{...}"
}]
```

## Security

### Authentication

> **Current state:** HTTP Basic against an in-memory user store, one user per role
> (`admin`/`admin`, `operations`/`operations`, `analyst`/`analyst`, `user`/`user`). There is no
> JWT issuance and no authorization server — `services/auth-server` is an empty module, and the
> `/api/v1/auth/login` endpoint the console calls is not implemented. See
> [phase-16](docs/phase-reports/phase-16-audit-and-remediation.md).

Authorization itself is real and enforced: `@EnableMethodSecurity` is wired, `@PreAuthorize`
rejects the wrong role, and `RbacMatrix` is consulted against the authenticated principal.
`ReplayControllerAuthorizationTest` covers allow, deny and anonymous cases.

### Authorization

RBAC matrix enforces permissions:
- **ADMIN**: All operations
- **OPERATIONS**: DLT replay, audit view
- **ANALYST**: Transaction view, reconciliation view
- **USER**: Read-only transaction view

Violations return `403 Forbidden`.

### Data Protection

- **PII Redaction**: SSN, account numbers, card numbers redacted in logs
- **Encryption**: Database connections use SSL; Kafka uses SASL/SSL (prod)
- **Audit Trail**: Immutable event log with cryptographic integrity checks

## Troubleshooting

### Services Won't Start

```bash
# Check logs
docker-compose logs transaction-service

# Common issues:
# - Port 5432 (Postgres) already in use
# - Port 9092 (Kafka) already in use
# - Insufficient memory

# Free ports
lsof -i :5432
lsof -i :9092
```

### Database Migrations Failed

```bash
# Manually check migrations
docker exec -it ledgerguard-postgres psql -U postgres -d ledgerguard \
  -c "SELECT * FROM flyway_schema_history;"

# Reset database (development only)
docker-compose down -v
docker-compose up -d
```

### Tests Failing Locally But Passing in CI

```bash
# Usually due to timing or Docker issues
# Run with verbose output
mvn test -X

# Check Docker is running
docker ps

# Rebuild cache
mvn clean
```

### High Latency / Slow Queries

```bash
# Check metrics in Prometheus
http://localhost:9090

# Look for:
# - High projection_lag_ms (>5 seconds)
# - High dlt_depth_count (backlog building)
# - High transaction ingestion p99 latency (>1000ms)

# Query logs by trace
grep "trace-xyz" logs/app.log | grep "duration"
```

## Performance Baselines

See [PERFORMANCE.md](docs/performance-baselines.md) for detailed baseline metrics.

**Summary**:
> **These are targets, not measurements.** No k6 scenario has been executed yet, so there is no
> observed throughput or latency figure for this system. Treat the numbers below as the thresholds
> the scenarios in `perf/` will assert against once run.

- Transaction ingestion: target 1000+ txn/s (p95 <500ms)
- Reconciliation queries: target 500+ qps (p95 <1000ms)
- Projection lag: target <5 seconds under normal load
- E2E flow: target <2 seconds (ingest → query → reconcile)

## Documentation

- **[Architecture Decision Records](docs/adr/)**: Design rationales
- **[Phase Reports](docs/phase-reports/)**: Implementation details by phase
- **[CONTRIBUTING.md](CONTRIBUTING.md)**: Development guidelines
- **[DEPLOYMENT.md](DEPLOYMENT.md)**: Production deployment
- **[API.md](docs/API.md)**: Complete API reference

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) file for details.

## Support

For issues, questions, or contributions:
- Open a GitHub issue for bugs
- Start a discussion for design questions
- Submit PRs for features (see CONTRIBUTING.md)

---

**Built with ❤️ for financial reconciliation teams**
