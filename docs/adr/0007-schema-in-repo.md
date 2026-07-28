# 0007. Versioned JSON Schema in the repository, not a schema registry

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

Every message crossing a service boundary needs a contract, and that contract needs to evolve
without breaking consumers that have not been redeployed. The industry-standard answer is
Confluent Schema Registry with Avro or Protobuf. The alternative is to keep schemas in version
control and enforce compatibility in CI.

## Decision drivers

- A breaking schema change must fail the **build**, not production.
- The contract must be reviewable in a pull request alongside the code that changes it.
- Resource budget (§0.1) — the registry is another container.
- Consumers must be able to read old event versions, forever, for the event-sourced aggregate
  (ADR-0003).

## Considered options

1. **Confluent Schema Registry + Avro.**
2. **Versioned JSON Schema files in the `contracts` module, compatibility enforced in CI** (chosen).
3. **No formal schema** — POJOs and hope.

## Decision outcome

**Chosen: option 2.**

### Layout

```
libs/contracts/src/main/resources/schemas/
  <eventType>/
    v1.json
    v2.json
```

One directory per event type, one file per version. The envelope has its own schema. Schemas are
**immutable once committed** — a change means a new version file, never an edit to an existing one.

### Enforcement in CI

Three tests, all of which fail the build rather than warn:

1. **Backward compatibility.** Loads the previously committed schema versions and asserts new
   versions only *add optional fields* or *widen types*. Removing a field, making an optional
   field required, or narrowing a type is a breaking change and requires a new `eventVersion`
   plus a documented dual-publish or upcasting path.
2. **Catalog completeness.** An event type that exists in code but has no entry in
   `docs/event-catalog.md` fails the build. Documentation that can silently drift is documentation
   nobody trusts.
3. **Every published type has a consumer and a schema.** Prevents orphaned events accumulating.

### Upcasting

Consumers transform old versions to the current internal shape on read. §2.4 requires **at least
one real v1→v2 upcast with tests**, so the mechanism is demonstrated rather than described. An
upcaster that exists only in prose is not a mechanism.

### Consequences

**Positive**

- The contract lives next to the code and changes in the same pull request — a reviewer sees the
  schema change and the producer change together.
- No extra container; nothing to run in CI beyond the tests.
- Compatibility violations surface at build time, on the contributor's machine, before merge.
- Schema history is git history: `git log` on a schema file is its evolution.

**Negative**

- **No runtime enforcement.** A registry rejects an incompatible message at produce time; we only
  catch it at build time. A producer that bypasses the shared serializer could publish anything.
  Mitigated by all publishing going through `common-kafka`, but this is a genuine weakening and is
  stated as such.
- **JSON is larger and slower than Avro or Protobuf on the wire.** At this throughput it does not
  matter; at high volume it would.
- Compatibility checking is **our code**, so it is only as good as the tests. Confluent's
  implementation is far more thoroughly exercised than ours will be.
- No central discovery for teams outside this repository. Fine for a single-repo system;
  inadequate for an organisation with many producers.

## Pros and cons of the options

### Option 1 — Confluent Schema Registry + Avro

- Good: runtime enforcement at produce and consume time.
- Good: mature, well-tested compatibility engine with configurable modes.
- Good: compact binary encoding; schema evolution is a solved problem.
- Bad: another container (~300 MB) against the budget.
- Bad: Avro code generation adds a build step and makes the event types less readable in a diff.
- Bad: schemas become a runtime deployment artifact, so "which schema is registered in this
  environment?" becomes a real operational question — the thing this decision most wants to avoid.
- **Verdict: the right answer for a multi-team organisation. Overkill for a single repository
  where every producer and consumer is in the same reactor.**

### Option 2 — schema in repo (chosen)

- Good: reviewable, versioned with the code, no infrastructure.
- Bad: build-time enforcement only.

### Option 3 — no formal schema

- Bad: "the contract is whatever the producer's POJO serialises to today." Every consumer breaks
  silently on a rename. Listed only to note that it is the default state of most systems that
  claim to be event-driven.

## More information

- Envelope definition and field semantics: `docs/event-catalog.md`
- Event-sourced aggregate's forever-readable requirement: `docs/adr/0003-event-sourcing-scope.md`
