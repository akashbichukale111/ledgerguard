# Phase 5 — Reconciliation Engine and Saga Orchestration

**Date executed:** 2026-07-28
**Entry commit:** `3473e3a` (Phase 4)

---

## 1. Acceptance gate — executed output

**The gate (§14):** *"saga success, saga failure→compensation, saga timeout, duplicate-event, and
all per-rule tests pass; determinism property test passes; PIT score on the engine is recorded
honestly."*

```
$ mvn -B -pl services/reconciliation-service -am verify
[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0   (engine unit + jqwik property)
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0   (SagaOrchestratorIT, real PostgreSQL)
[INFO] BUILD SUCCESS
EXIT=0
```

| Gate element | Test | Status |
|---|---|---|
| Saga success | `sagaHappyPath`, `stepAttemptsArePersisted` | Pass |
| Saga failure → compensation | `stepFailureCompensates`, `compensationRunsInReverseOrder` | Pass |
| Compensation failure surfaces | `compensationFailureSurfaces` | Pass |
| Saga timeout (fixed clock) | `sagaTimesOut`, `completedSagasAreNotSwept` | Pass |
| Duplicate event | `duplicateEventDoesNotStartASecondSaga`, `processedEventPreventsReprocessing`, `tripleDeliveryIsIdempotent` | Pass |
| Per-rule tests | 30 across 8 nested classes | Pass |
| Determinism property test | 6 jqwik properties × 200 tries | Pass |
| PIT score recorded honestly | §4 — **65%** | Recorded |

---

## 2. PIT mutation score — the real number

```
>> Line Coverage (for mutated classes only): 323/345 (94%)
>> Generated 145 mutations Killed 94 (65%)
```

| Class | Killed / Generated | Score |
|---|---|---|
| `BusinessCalendar` | 11/11 | **100%** |
| `MatchingConfig` | 1/1 | 100% |
| `ReconciliationEngine` | 59/85 | 69% |
| `ReconciliationEngine$Rule` | 2/3 | 66% |
| `ReferenceNormaliser` | 18/31 | 58% |
| `MatchClassification` | 2/4 | 50% |
| `MatchExplanation` | 1/7 | **14%** |
| `MatchResult` | 0/2 | **0%** |
| **Total** | **94/145** | **65%** |

**This is reported as measured, not as a target that was hit.** §0.3 forbids writing a number that
was not produced, and that cuts both ways: 65% is a middling score and saying so is the point.

What the gap means, specifically:

- **94% line coverage against a 65% mutation score** is the whole argument for mutation testing.
  A third of the mutations survive on lines the tests *execute*, which means those lines are run
  but their behaviour is not asserted.
- **`MatchExplanation` at 14%** is the weakest area and the most defensible one to have found:
  the surviving mutants are almost entirely inside `narrative()`, the human-readable prose
  renderer. The tests assert the narrative is non-blank but not its wording. Asserting exact prose
  would make the tests brittle against harmless rewording, so the low score here reflects a
  deliberate trade rather than an oversight — but it is a real gap and it is recorded as one.
- **`MatchResult` at 0%** (2 mutations) is a genuine gap: the invariant that an auto-resolvable
  classification must name a matched entry is enforced in the compact constructor but never tested
  for the *rejection* path.
- **`BusinessCalendar` at 100%** is the number that matters most operationally — business-day
  arithmetic is where an off-by-one produces a break every Monday.

**Improving this is carried into Phase 11**, not quietly dropped.

### Getting PIT to run at all

PIT's coverage minion died with `UNKNOWN_ERROR` on every attempt until the JUnit 5 plugin version
was aligned: `pitest-junit5-plugin` 1.2.1 is incompatible with PIT 1.17.4. Bumping to 1.2.2 fixed
it. Recorded because the error message names neither the plugin nor the version.

The run also excludes the Testcontainers saga IT (needs Docker inside the minion) and the jqwik
property test (its own JUnit engine). Both exclusions are scope decisions, not skipped tests — they
run in full under `mvn verify`.

---

## 3. Three real engine bugs, found by tests

### 3.1 Blocking silently defeated the basis-point tolerance

The most serious defect in this phase. A fixed-width blocking bucket is fundamentally incompatible
with a **relative** tolerance:

```
expected: MATCHED_WITH_TOLERANCE
 but was: MISSING_EXTERNAL
```

USD 1,000,000.00 and USD 1,000,400.00 are well inside the 5 bps tolerance (500.00) but land four
100-wide buckets apart, so they were **never compared**. Blocking was overriding the matching rules
for exactly the high-value payments where a missed match costs most — and it would have looked like
a data problem, not a code problem, in production.

**Fix:** the candidate scan now spans every bucket the widest applicable tolerance can reach,
computed from the entry's own amount. Cost scales with the tolerance, which is the correct trade.

### 3.2 `REFERENCE_MATCH` ignored the settlement window, making `DATE_TOLERANCE_MATCH` dead code

```
expected: DATE_MISMATCH
 but was: AUTO_MATCHED
```

The rule fired on reference + amount alone with no date constraint, so entries **three weeks apart
auto-matched** — and `DATE_TOLERANCE_MATCH`, sitting below it in the cascade, was unreachable.

**Fix:** every match rule is now gated on the settlement window, and the four rules are mutually
exclusive so each is reachable.

### 3.3 A test-fixture error, recorded as a test-fixture error

The fuzzy-match test used a *transposition* (`...12345` → `...12354`), which is **two** edits and
scores 0.867 — below the 0.88 threshold. The engine correctly declined to fire. The test input was
wrong, not the engine. Replaced with a single-character corruption (0.9375) and the distinction
noted in the test, because it is easy to get wrong.

---

## 4. A test that was passing for the wrong reason

Worth recording because it is the kind of thing that makes a green suite untrustworthy.

The saga IT originally constructed the orchestrator by hand:

```java
orchestrator = new SagaOrchestrator(instances, steps, compensations, CLOCK, TIMEOUT);
```

A manually-constructed instance **is not proxied**, so `@Transactional` never applies, no
transaction is opened, and every entity mutation is discarded. `sagaHappyPath` passed anyway —
because it asserted on the returned **in-memory** object, which had the right state despite nothing
being written to the database.

Only the three tests that re-read from the repository failed, which is what exposed it:

```
sagaTimesOut            expected: COMPENSATED  but was: STARTED
completedSagasAreNotSwept  expected: 0  but was: 1
stepAttemptsArePersisted   Expecting all elements of: ...
```

**Fix:** autowire the Spring-managed bean and override the `Clock` bean with the test's mutable
clock via `@TestConfiguration`. All 13 tests now assert against persisted state.

---

## 5. What was built

| Component | Detail |
|---|---|
| Matching engine | 6 rules in precedence order, blocking, deterministic tie-breaking, near-miss classification. **Pure domain — no Spring, no clock, no persistence.** |
| `MatchExplanation` | Rule ID, rule-set version, candidate pool size, per-field comparisons with actual deltas and applied tolerances, **and why competing candidates lost** |
| `ReferenceNormaliser` | Noise-prefix stripping, case folding, separator removal; Levenshtein similarity for fuzzy only |
| `BusinessCalendar` | Business-day arithmetic. Friday→Monday is **1** business day, not 3 calendar days |
| Saga orchestrator | Persisted state machine, one row per step *attempt*, reverse-order compensation, clock-driven timeout sweeper |
| Schema | `saga_instance`, `saga_step`, `compensation_record`, `processed_event`, `reconciliation_case` |

Design points worth defending:

- **Fuzzy matches are never auto-matched**, however high the similarity. A heuristic must not close
  a financial match.
- **Duplicate detection runs before the match rules.** The same reference + amount + counterparty
  presented twice is a duplicate presentation, not a match — treating it as a match settles a
  payment twice.
- **Compensation continues after one fails.** Abandoning the remaining compensations would leave
  worse partial state than what we started with. `compensationFailureSurfaces` asserts the second
  compensation still ran after the first failed.
- **Read-only steps declare themselves non-compensating**, so no no-op records clutter the incident
  view.

---

## 6. Known gaps carried forward — stated plainly

1. **`AGGREGATE_MATCH` (1:N) is specified but not implemented.** `MatchingConfig.maxAggregateSize`
   exists and is validated; no rule consumes it. Subset-sum with a bounded N is real work and
   shipping a broken version would be worse than shipping none. **Phase 11.**
2. **`MISSING_ENTRY_DETECTION` does not use an ageing threshold.** It fires when no candidate
   exists, not when an entry has been unmatched past a threshold — the latter needs a clock and a
   scheduled sweep. **Phase 7.**
3. **Public holidays are not modelled.** Weekends only. A payment settling across a bank holiday can
   still raise a spurious date break. `BusinessCalendar` takes a holiday set so a calendar can be
   supplied without changing a call site.
4. **The Kafka consumer is not wired.** The saga is driven directly in tests. The
   `TransactionReceived` consumer, the `processed_event` write in the same transaction as the
   effect, and the v1→v2 upcaster are **Phase 6/7**. `processed_event` and its constraints exist and
   are tested; what is missing is the listener that uses them.
5. **`ReconciliationException` and the analyst workflow are not implemented** — classifications are
   produced but no exception rows are raised, and there is no claim/resolve/force-match path.
6. **PIT at 65%**, with the weak areas identified in §2. **Phase 11.**
7. **Optimistic-lock retry** (carried from Phase 4) is still detection-only.
8. **`common-kafka` remains empty.**

## 7. Entry conditions for Phase 6

- [x] Matching engine deterministic and explainable, proven by property tests
- [x] Saga orchestration, compensation and timeout proven against real PostgreSQL
- [x] `processed_event` schema and constraints in place for the consumer to use
- [x] Phase 5 committed and pushed
