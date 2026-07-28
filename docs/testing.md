# Testing strategy

> **Status.** Populated progressively; completed in Phase 11 with real counts and a real PIT score.

## Philosophy

**Tests exist to catch defects, not to produce a coverage number.** A high coverage percentage over
assertion-free tests is worse than no metric, because it manufactures confidence.

Shape: many fast domain unit tests, a meaningful set of integration tests against real
infrastructure, a thin end-to-end layer.

## Layers

| Layer | Tool | Runs against |
|---|---|---|
| Domain unit | JUnit 5 + AssertJ | Pure Java, no Spring context |
| Property-based | jqwik | The reconciliation engine |
| Mutation | PIT | `Money` and the reconciliation engine **only** |
| Architecture | ArchUnit | The compiled bytecode |
| Integration | Testcontainers | Real PostgreSQL, Kafka, MongoDB |
| Contract | JUnit | Committed JSON Schemas |
| Frontend unit | Vitest + RTL + MSW | Components, all four async states |
| End-to-end | Playwright + axe | The running stack |
| Performance | k6 | The running stack |

## Why mutation testing, and why only two packages

PIT mutates the code and checks whether tests fail. It measures whether tests **assert**, rather
than whether they merely execute lines — a far stronger signal than coverage.

It is scoped to `Money` and the reconciliation engine because those are where a silent defect is
most expensive, and because PIT is slow. Applying it to Spring wiring would burn CI time to test
the framework.

**The score will be reported as measured. If it is 71%, this document will say 71%** — never a
target that was not hit (§0.3).

## ArchUnit rules

| Rule | Rationale |
|---|---|
| No `java.util.Date` / `Calendar` / `System.currentTimeMillis()` | All time is injected `Clock` + `Instant` |
| No `double` / `float` in domain packages | [ADR-0009](adr/0009-monetary-representation.md) |
| Controllers must not depend on repositories | The most common layering decay path |
| No cross-service package imports | Services communicate by events, not classpath |
| No `System.out` / `System.err` / `printStackTrace()` | Structured logging only |
| Domain layer free of Spring / JPA / Jackson / Kafka | Keeps the domain testable in isolation |

## Deliberately NOT tested, and why

Honesty about scope is part of the strategy:

- **Framework behaviour.** No tests that Spring injects beans or that Jackson serialises a record.
- **Getters and setters.** Testing them inflates coverage and catches nothing.
- **Kafka's own delivery semantics.** We test *our* consumers' idempotency, not that Kafka works.
- **Third-party library internals.**
- **The UI's visual appearance.** Snapshot tests of rendered pixels are brittle. Behaviour,
  accessibility, and the four async states are tested instead.

## Running the layers

*Exact commands recorded in Phase 11 once the suite exists. `make verify` runs what CI runs.*
