# Phase 3 — Contracts and Shared Foundations

**Date executed:** 2026-07-28
**Branch:** `claude/ledgerguard-master-build-d4bpzz`
**Entry commit:** `c06148b` (Phase 2)

---

## 1. Acceptance gate — executed output

**The gate (§14):** *"`Money` and identifier tests pass including all edge cases, the
schema-compatibility test passes, and ArchUnit rules are active."*

```
$ mvn -B clean verify
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0    (contracts)
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0    (common-core)
[INFO] BUILD SUCCESS
[INFO] Total time:  10.487 s
EXIT=0
```

**69 tests, 0 failures, 0 skipped.** Nothing is `@Disabled`; nothing is excluded.

| Gate element | Tests | Status |
|---|---|---|
| `Money` edge cases | 43 | Pass |
| Identifier (UUIDv7) | 11 | Pass |
| Schema compatibility | 7 | Pass |
| Event catalog completeness | 2 | Pass |
| ArchUnit rules active | 6 | Pass — **and proven to fire**, §4 |

---

## 2. What was built

| Module | Delivered |
|---|---|
| `common-core` | `Money`, `CurrencyCode`, `CurrencyMismatchException`, `Uuid7`, `ErrorCode` (20 codes), `DomainException`, `IllegalStateTransitionException`, ArchUnit rules |
| `contracts` | `EventEnvelope` record, envelope JSON Schema, `TransactionReceived` v1 **and v2**, compatibility test, catalog-completeness test |
| `common-kafka`, `common-observability`, `common-security` | Unchanged — see §6 |

### `Money` — the rules that are enforced rather than described

| Rule | Test |
|---|---|
| Scale from ISO 4217 minor units — JPY 0, USD 2, BHD 3 | `Scale.minorUnitsComeFromIso4217` (7 currencies) |
| Excess precision **rejected**, never truncated | `PrecisionRejection.usdRejectsAThirdDecimal` |
| Trailing zeros are *not* excess precision | `PrecisionRejection.trailingZeroesAreNotExcessPrecision` |
| Cross-currency arithmetic throws | `CurrencyIsolation.*` (4 tests) |
| `equals` uses `compareTo` semantics — `2.50` equals `2.5` | `Equality.scaleDifferencesDoNotBreakEquality` |
| `HALF_EVEN` does not bias upward like `HALF_UP` | `Arithmetic.halfEvenDoesNotBiasUpwardTheWayHalfUpDoes` |
| Rounding mode always explicit — no default anywhere | signature of `multipliedBy` / `dividedBy` |
| Exact addition; no drift over 1000 operations | `Arithmetic.repeatedAdditionDoesNotDrift` |
| Never scientific notation | `Representation.plainStringNeverUsesScientificNotation` |

Two decisions worth flagging because they go beyond the specification:

- **Currencies with undefined minor units are rejected outright.** `XAU` (gold) reports `-1`
  fraction digits from the JDK. An amount whose scale is undefined cannot be stored in
  `NUMERIC(19,4)` with any meaning, so `CurrencyCode.of("XAU")` throws at construction rather than
  producing a value that misbehaves later.
- **`equals` never throws on a currency mismatch** even though `compareTo` does. Collections rely
  on `equals` being total; a `HashMap` lookup that throws would be a far worse bug than the one
  being prevented.

### `Uuid7` — the ordering property, tested directly

Java 21 has no v7 generator, so this is ours, which makes correctness our problem. A subtly wrong
implementation silently destroys the ordering that is the *entire* justification for ADR-0010.

The load-bearing test is `idsMintedInTheSameMillisecondStillSortInGenerationOrder`: 2000 IDs
generated against a **frozen clock**, then sorted, then asserted equal to generation order. A naive
implementation that randomises `rand_a` fails this immediately. This one uses `rand_a` as a
monotonic counter (RFC 9562 §6.2), giving a total order for 4096 IDs per millisecond.

Also covered: version/variant bits, timestamp round-tripping, ordering across millisecond
boundaries, **backwards clock steps** (NTP corrections happen — ordering degrades gracefully rather
than breaking), 50,000 single-threaded IDs with no collision, 40,000 IDs across 8 threads with no
collision, and counter exhaustion waiting rather than wrapping.

The comparison helper encodes a trap worth stating: `UUID.compareTo` compares the halves as
**signed** longs, which puts a UUID with the high bit set *before* one without. Using it would make
the ordering tests pass or fail arbitrarily. The tests compare unsigned.

---

## 3. Schema compatibility — enforcement, not aspiration

`TransactionReceived` ships at **v1 and v2**, so the compatibility machinery has real input rather
than a single version it can trivially accept. v2 adds `postingDate` and `settlementSystem`, both
optional and nullable.

Five rules are enforced between consecutive versions:

1. No field removed
2. No new required field
3. No optional field promoted to required
4. Types may widen (`"string"` → `["string","null"]`), never narrow
5. Enums may gain members, never lose them

Plus two cross-cutting checks: every monetary field must be a **string, not a JSON number**
(ADR-0009 — `JSON.parse` yields an IEEE-754 double and destroys the value at the browser boundary),
and the envelope must require the fields that dedupe, ordering, and correlation depend on.

**The rules are self-checked.** `rulesRejectBreakingChanges` feeds synthetic schemas through the
comparator and asserts each rule fires. A compatibility test that has only ever seen compatible
input proves nothing about its own logic — it would pass just as happily if the comparator were
empty.

The **v1 → v2 upcaster** is specified in `docs/event-catalog.md` with its defaulting rules and the
reasoning: `postingDate` defaults to `valueDate` because for a v1 message the two were the same by
construction; `settlementSystem` becomes `null` because the information was genuinely not captured,
and inventing a plausible default would be worse than an honest null — downstream logic could not
tell the difference. *(The upcaster's implementation lands in Phase 4 with the consumer that needs
it; §6.)*

---

## 4. ArchUnit rules are active — and proven to fire

A rule that passes because nothing violates it is indistinguishable from a rule that does not work.
So a deliberate violation was introduced and the rules were run against it:

```java
public class TEMP_Violation {
    public Double badMoney = 1.0d;
    public Date badTime = new Date();
    public void shout() { System.out.println("bad"); }
}
```

```
Tests run: 6, Failures: 3

Rule '...java.lang.Double... because monetary values must be BigDecimal' was violated (2 times):
  Field <TEMP_Violation.badMoney> has type <java.lang.Double>
Rule '...java.util.Date... because all time is UTC java.time.Instant' was violated (2 times):
  Field <TEMP_Violation.badTime> has type <java.util.Date>
Rule '...System.out... because all output is structured JSON logging' was violated (1 times):
  Method <TEMP_Violation.shout()> gets field <java.lang.System.out>
```

The file was then deleted and the suite returns to `Tests run: 60, Failures: 0`.

Six rules currently active on `common-core`: no `Double`/`Float`, no `java.util.Date`/`Calendar`/
`java.sql.Timestamp`, no `System.currentTimeMillis()`/`Instant.now()`/`LocalDateTime.now()`, no
framework dependency at all, no `System.out`/`err`/`printStackTrace`, no `java.util.Random`.

**Scope stated honestly:** these cover `common-core`, the module with the strictest constraint.
The service-level rules — controllers not depending on repositories, no cross-service package
imports, layer dependencies pointing inward — are added *with* those services in Phases 4–6. A rule
about classes that do not exist passes vacuously and would be theatre.

---

## 5. Defects found and fixed

| # | Defect | How it surfaced | Fix |
|---|---|---|---|
| 1 | The compatibility self-check expected the wrong rule's message | `rulesRejectBreakingChanges` failed | Rule 2 (no new required field) legitimately fires before rule 3 when a field is both newly-required *and* previously-optional. The assertion now targets rule 2, and a separate case exercises rule 3 in isolation. **The rules were right; the test's expectation was wrong.** |
| 2 | `docs/event-catalog.md` had no entry for `TransactionReceived` | `catalogRecordsCurrentVersions` failed | Catalog populated with the full field table, both versions, and the upcaster specification. **This is the drift-prevention mechanism working on its first real opportunity.** |
| 3 | Formatting violations on new sources | `spotless:check` failed the build twice | `mvn spotless:apply`. The check stays bound to `verify`. |

---

## 6. What was deliberately NOT built in this phase

`common-kafka`, `common-observability`, and `common-security` still contain only their
`package-info.java`. Stating that plainly rather than claiming five completed modules.

Each is, by its nature, **Spring infrastructure**: envelope serdes and consumer factories, tracing
propagators and MDC filters, resource-server configuration and redaction serializers. None can be
written meaningfully — let alone tested meaningfully — before there is a service to configure. The
alternative would be writing configuration classes nobody instantiates, which is exactly the "wide
surface of stubs" §0.7 warns against.

They are built in the phases that consume them:

| Module | Built in | With |
|---|---|---|
| `common-kafka` | Phase 4 → 7 | Envelope serde and the idempotent consumer (4); retry/DLT topology (7) |
| `common-observability` | Phase 9 | Tracing, Kafka header propagation, MDC, metrics |
| `common-security` | Phase 8 | Resource server, RBAC, redaction |

Also deferred, each to the phase that needs it:

- **The v1 → v2 upcaster implementation.** Specified in the catalog with its defaulting rules;
  implemented in Phase 4 alongside the consumer that applies it. §2.4 requires it working with
  tests, and that requirement is carried forward, not dropped.
- **Runtime schema validation.** The schemas are enforced at *build* time today. Wiring validation
  into the serde is Phase 4. ADR-0007 already states this is weaker than a registry.
- **Typed identifier wrappers** (`TransactionId`, `CaseId`). `Uuid7` generates them; whether the
  extra type-safety earns its mapping cost is decided in Phase 4 against real call sites rather
  than guessed at now.

---

## 7. Acceptance criteria

| Criterion (§14 Phase 3) | Status | Evidence |
|---|---|---|
| `Money` tests pass including all edge cases | **Met** | §1, §2 — 43 tests: zero, negative, max scale, currency mismatch, precision rejection, tolerance boundaries |
| Identifier tests pass | **Met** | §2 — 11 tests including frozen-clock monotonicity and 8-thread concurrency |
| Schema-compatibility test passes | **Met** | §3 — 7 tests over two real versions, with the rules self-checked |
| ArchUnit rules active | **Met** | §4 — 6 rules, demonstrated firing on a deliberate violation |
| `contracts` module with envelope + schemas | **Met** | `EventEnvelope`, envelope schema, `TransactionReceived` v1+v2 |
| `common-core` with `Money`, IDs, `Clock`, errors | **Met** | Clock is injected via constructor (`Uuid7`), enforced by ArchUnit |
| `common-kafka` / `-observability` / `-security` | **Deferred with reasons** | §6 |

---

## 8. Known gaps carried into Phase 4

1. Three shared modules are still empty (§6) — built in Phases 4, 7, 8, 9.
2. The v1→v2 upcaster is specified but not implemented — Phase 4.
3. Schemas are not validated at runtime — Phase 4.
4. Service-level ArchUnit rules do not exist yet — Phases 4–6.
5. `Money` has no JPA converter or Jackson serializer yet. Both are adapter concerns and are added
   with the first persistence and HTTP layers in Phase 4; `common-core` stays framework-free.
6. No property-based tests yet. jqwik is in `dependencyManagement`; it is applied to the
   reconciliation engine in Phase 5, where determinism under input reordering is the property that
   matters.

## 9. Entry conditions for Phase 4

- [x] `Money` correct and thoroughly tested — every persistence and API decision depends on it
- [x] UUIDv7 generation verified for ordering, the property indexes rely on
- [x] Event envelope and schema-evolution mechanism in place with enforcement
- [x] Error-code catalogue defined for RFC 9457 mapping
- [x] ArchUnit active and demonstrated to fire
- [x] PostgreSQL schema (outbox, idempotency, processed_event) applied and verified in Phase 2
- [x] Phase 3 committed and pushed
