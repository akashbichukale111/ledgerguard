# 0013. Hexagonal-ish layering, enforced by ArchUnit rather than convention

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

Layered architectures decay. A developer under deadline pressure injects a repository into a
controller because it is two lines instead of twenty. Nothing fails. The next developer copies the
pattern. Eighteen months later the "layers" exist only in the README.

The decision is not *whether* to have layers — it is **what enforces them**.

## Decision drivers

- The domain model must be testable without Spring, a database, or a broker.
- Layer violations must fail the build, not a code review.
- The structure must not become ceremony: a mapper per layer per entity is a cost that must earn
  its place.

## Considered options

1. **Classic three-layer** (controller → service → repository), enforced by convention.
2. **Strict hexagonal / clean architecture** — ports and adapters throughout, domain entities never
   leave the core, mappers at every boundary.
3. **Hexagonal-ish: a pure domain core, adapters at the edges, enforced by ArchUnit** (chosen).

## Decision outcome

**Chosen: option 3.** Per service:

```
dev.ledgerguard.<service>
├── domain/          pure Java: aggregates, value objects, domain services,
│                    state machines, the reconciliation rules
├── application/     use cases / command handlers; orchestrates domain + ports;
│                    owns transaction boundaries
├── adapter/
│   ├── in/          REST controllers, Kafka consumers, scheduled triggers
│   └── out/         JPA repositories, Kafka producers, HTTP clients
└── config/          Spring wiring
```

Dependencies point **inward**: `adapter → application → domain`. The domain depends on nothing.

### What "-ish" means, stated honestly

This is not doctrinaire hexagonal architecture, and pretending otherwise would be a claim the code
does not support. Two deliberate compromises:

**1. JPA entities are separate from domain aggregates only where it earns its place.** Strict
hexagonal demands a persistence model distinct from the domain model with a mapper between them.
For `ReconciliationCase` — event-sourced, with rich invariants — that separation is genuinely
worth it. For `OutboxRecord` and `IdempotencyRecord`, which are infrastructure ledgers with no
domain behaviour, a mapper would be pure ceremony, and those are JPA entities used directly.

**2. The application layer uses Spring annotations** (`@Service`, `@Transactional`). Transaction
demarcation is genuinely an application-layer concern and expressing it declaratively is clearer
than a hand-rolled unit-of-work. **The domain layer has no Spring.**

### Enforcement — the actual decision

Convention does not enforce anything. These ArchUnit rules run as part of `mvn verify` and fail
the build:

| Rule | Why |
|---|---|
| Domain packages must not depend on Spring, JPA, Jackson, or Kafka | Keeps the domain testable in isolation and framework-independent |
| Controllers must not depend on repositories | The classic layer-skip; the single most common decay path |
| No `java.util.Date`, `Calendar`, or `System.currentTimeMillis()` anywhere | ADR: all time is injected `Clock` + `Instant` |
| No `double`/`float` in domain packages | ADR-0009 |
| No cross-service package imports | Services communicate by events, never by classpath |
| No `System.out`/`System.err`, no `printStackTrace()` | Structured logging only |
| Layer dependencies point inward | The layering itself |

**These rules are the deliverable of this ADR.** The package structure is the easy part; the tests
are what make it survive contact with a deadline.

### Consequences

**Positive**

- Domain logic — the reconciliation engine, `Money`, the state machines — is unit-testable with no
  Spring context, so those tests run in milliseconds.
- Layer violations fail on the contributor's machine before review.
- Swapping an adapter (Postgres → something else) does not touch domain code.

**Negative**

- **More files and more indirection than a three-layer design.** A simple read-and-return use case
  passes through more types than it strictly needs. This is a real, recurring cost paid on every
  feature.
- **Mapping code.** Where the domain and persistence models are separate, mappers must be written
  and maintained. They are tedious and a place bugs hide, which is why they are limited to where
  the separation earns its place.
- ArchUnit rules **can become an obstacle** when a legitimate exception arises. The discipline is
  that an exception requires either changing the rule (with justification in the commit) or
  changing the design — never an unexplained suppression.
- The `-ish` requires judgement, and judgement is inconsistent across contributors. A stricter rule
  is easier to apply mechanically; this one needs the reasoning above to be read.

## Pros and cons of the options

### Option 1 — classic three-layer by convention

- Good: least ceremony; familiar to every Java developer; fastest to write.
- Bad: nothing prevents decay. "Service" becomes a bag of transaction scripts and the domain model
  becomes anaemic — entities with getters and setters and no behaviour, which is where most
  "layered" codebases end up.
- Bad: business logic entangled with Spring means slow tests and a domain that cannot be reasoned
  about independently.

### Option 2 — strict hexagonal

- Good: maximum isolation; the domain is genuinely framework-free everywhere.
- Bad: a mapper per entity per boundary is significant, permanent overhead.
- Bad: for infrastructure ledgers with no domain behaviour, the indirection buys nothing at all.
- Bad: high ceremony discourages small, correct changes — the architecture starts costing more
  than it protects.

### Option 3 — hexagonal-ish + ArchUnit (chosen)

- Good: the isolation that matters, where it matters; enforcement that survives deadlines.
- Bad: requires judgement about where separation earns its place.

## More information

- ArchUnit rule definitions: `docs/testing.md`
- Domain model: `docs/domain-model.md`
