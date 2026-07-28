# Phase 4 — Ingestion, Persistence, Outbox

**Date executed:** 2026-07-28
**Branch:** `claude/ledgerguard-master-build-d4bpzz`
**Entry commit:** `a02ef84` (Phase 3)

---

## 1. Acceptance gate — executed output

**The gate (§14):** *"Testcontainers integration tests prove atomic aggregate+outbox writes,
idempotent replay, and optimistic-lock retry; a real event lands on a real Kafka topic and is
asserted by a consumer in the test."*

```
$ mvn -B clean verify
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0   (contracts)
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0   (common-core)
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0   (WritePathIT — real Postgres + real Kafka)

[INFO] transaction-service ................................ SUCCESS [ 25.515 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  36.912 s
EXIT=0
```

**79 tests, 0 failures, 0 skipped, nothing disabled.**

| Gate element | Test | Status |
|---|---|---|
| Atomic aggregate + outbox write | `aggregateAndOutboxAreWrittenAtomically` | Pass |
| Rollback leaves no partial state | `failureLeavesNoPartialState` | Pass |
| Ledger entries balance | `ledgerEntriesBalance` | Pass |
| Idempotent replay | `duplicateRequestIsIdempotent` | Pass |
| Same key, different body → 409 | `sameKeyDifferentBodyConflicts` | Pass |
| Field-order-independent idempotency | `canonicalisationIgnoresFieldOrder` | Pass |
| Concurrent duplicates → one aggregate | `concurrentDuplicatesCreateOneAggregate` | Pass |
| **Real event on real Kafka, read by a real consumer** | `eventIsPublishedToKafkaAndConsumed` | Pass |
| Published records are not resent | `publishedRecordsAreNotResent` | Pass |
| Optimistic locking prevents lost updates | `optimisticLockingPreventsLostUpdates` | Pass |

Tests run against **Testcontainers PostgreSQL 16 and Kafka 3.8.1**, not in-memory substitutes. H2
does not enforce the same constraints and implements `FOR UPDATE SKIP LOCKED` differently; it would
have let several of these pass while the production code was broken.

---

## 2. What was built

| Layer | Delivered |
|---|---|
| Schema | `V2` (transaction, ledger_entry), `V3` (idempotency response fix — §4.4) |
| Domain | `TransactionStatus` with an explicit transition allow-table; `Direction` |
| Persistence | `TransactionEntity` (`@Version`), `LedgerEntryEntity`, `OutboxRecordEntity`, `IdempotencyRecordEntity`, `MoneyAmountConverter`, repositories with a `SKIP LOCKED` claim query |
| Application | `SubmitTransactionHandler` — the single-transaction write path |
| Messaging | `KafkaEventPublisher` (synchronous ack, envelope wrapping, header propagation), `OutboxPoller` |
| Web | `TransactionController`, `SubmitTransactionRequest`, `ProblemDetailsHandler` (RFC 9457) |

### The design decisions worth defending

**One transaction, four writes.** `SubmitTransactionHandler.handle` writes the idempotency record,
the aggregate, its ledger entries, and the outbox row in a single ACID transaction. Splitting any
of them — particularly "publish then save" — reintroduces the dual-write bug the outbox exists to
prevent. `failureLeavesNoPartialState` proves the rollback: an amount with excess precision leaves
zero rows in all three tables.

**The idempotency guarantee rests on a constraint, not a read.** The handler claims the key by
inserting it, and treats the resulting constraint violation as the replay signal. A check-then-act
in application code has a window between the check and the act;
`concurrentDuplicatesCreateOneAggregate` fires 8 concurrent identical requests and asserts exactly
one is accepted.

**Publishing blocks on the broker ack.** `KafkaEventPublisher` calls `.get(10s)` on the send future.
Fire-and-forget would let the poller mark a record published before the broker had it, silently
converting at-least-once into at-most-once.

**One record at a time, not batched.** A batch that fails halfway leaves an ambiguous set of
published rows. At this scale, resolving that ambiguity costs more than the throughput saved.

**A failed publish does not roll back its batch.** The poller catches per-record, leaves
`published_at` null, and continues. Rethrowing would roll back rows the broker had already
acknowledged, guaranteeing duplicates on the next poll.

**The failure window is documented in the code, not hidden.** `OutboxPoller`'s javadoc states
plainly that a crash between the Kafka ack and the `published_at` update republishes the event, and
that this is inherent rather than a defect.

---

## 3. Evidence for the headline claims

**A real event, on a real topic, read by a real consumer** (`eventIsPublishedToKafkaAndConsumed`):

- message key `== transactionId` — this is what gives per-aggregate ordering
- envelope `eventType=TransactionReceived`, `eventVersion=2`, non-blank `eventId` and `correlationId`
- **`payload.amount` is a JSON string, asserted with `isTextual()`**, value `"1250.75"`. A JSON
  number would be an IEEE-754 double by the time a browser parsed it (ADR-0009).
- Kafka headers carry `correlationId` and `eventType` across the broker hop

**Optimistic locking** (`optimisticLockingPreventsLostUpdates`): 6 threads read the same aggregate
and write concurrently; the test asserts `version == successfulWrites`. A lost update would show a
version lower than the number of successes.

---

## 4. Defects found and fixed — all at the cause

Five, each caught by a test or by a check that was doing its job.

### 4.1 `repackage` broke the integration-test classpath

```
Caused by: java.lang.NoClassDefFoundError:
    dev/ledgerguard/transaction/adapter/out/persistence/LedgerEntryEntity
```
`spring-boot-maven-plugin:repackage` **replaces** `target/*.jar` with a fat jar whose classes live
under `BOOT-INF/classes`. Failsafe runs after `package` and resolves the module's own artifact, so
it could no longer see the application classes.

**Fix:** `<classifier>boot</classifier>`. The plain jar stays for the test classpath; the runtime
image will use the `-boot` jar.

### 4.2 `ddl-auto: validate` caught schema/entity drift — twice

```
Schema-validation: wrong column type encountered in column [request_body_hash]
  in table [idempotency_record]; found [bpchar (Types#CHAR)], but expecting [varchar(64)]
Schema-validation: wrong column type encountered in column [currency]
  in table [ledger_entry]; found [bpchar (Types#CHAR)], but expecting [varchar(3)]
```

The migrations declare `CHAR(64)` and `CHAR(3)` — correct, because a hex SHA-256 is always exactly
64 characters and an ISO 4217 code is always 3. Hibernate defaults a `String` to `varchar`.

**Fix:** `@JdbcTypeCode(SqlTypes.CHAR)` on the three fields. **The migrations were not edited** —
they are committed and therefore immutable by the discipline stated in
`docs/adr/0005-transactional-outbox.md` and in the migration headers themselves.

This is `ddl-auto: validate` earning its place. With `ddl-auto: none` the mismatch would have
surfaced as subtly wrong comparison behaviour in production.

### 4.3 Spring Data `merge()` silently discarded the idempotency completion

The most interesting defect in this phase, and it presented as a *correct-looking* failure: every
legitimate replay returned `409 IN_FLIGHT` instead of `200`.

`IdempotencyRecordEntity` has an **assigned** (non-generated) `String` id and no `@Version`. Spring
Data's `isNew()` therefore reports false, so `save()` calls `merge()` rather than `persist()`.
`merge()` returns a **different managed instance** and leaves the argument detached — so
`claim.complete(...)` mutated a detached object and was discarded at commit. The record stayed
`IN_FLIGHT` forever.

**Fix:** use the instance returned by `saveAndFlush`, with a comment explaining why, because the
next person to touch this will make the same assumption.

### 4.4 `jsonb` normalisation broke byte-identical replay

```
expected: {"status":"RECEIVED","transactionId":"019fa696-..."}
 but was: {"status": "RECEIVED", "transactionId": "019fa696-..."}
```

`response_body` was `JSONB` in V1. PostgreSQL's `jsonb` **normalises on write** — reordering keys
and re-spacing. The replay was semantically equal but not byte-equal, and §5.1 requires the
*original response*. A client that hashes or signature-checks the body would see them differ.

**Fix: migration `V3`**, altering the column to `TEXT`. `jsonb` is the wrong type for an opaque
previously-sent payload we never query into. V1 was not edited — this is the fix-forward discipline
working as designed, on its first real occasion.

### 4.5 Duplicate bean definitions

`KafkaEventPublisher` and `OutboxPoller` carried `@Component` *and* were constructed by
`@Bean` methods, so component scanning tried to autowire a bare `String topic` and a bare `int`.
**Fix:** removed the annotations; both are configuration-constructed, and the javadoc now says why.

### 4.6 A test bug, fixed honestly as a test bug

`eventIsPublishedToKafkaAndConsumed` took the *first* record on the topic. The topic is shared
across tests in the class and a fresh consumer group starting at `earliest` read an earlier test's
event. **Fix:** match on the message key. This was the test being wrong, not the production code,
and it is recorded as such rather than quietly patched.

---

## 5. Acceptance criteria

| Criterion (§14 Phase 4) | Status | Evidence |
|---|---|---|
| Command API | **Met** | `POST /api/v1/transactions`, `Idempotency-Key` required |
| Validation | **Met** | Bean Validation at the edge **plus** domain invariants re-asserted in the command |
| Aggregate | **Met** | `TransactionEntity` + balanced `LedgerEntryEntity` pair |
| Idempotency ledger | **Met** | 4 tests incl. concurrent race and field-order independence |
| Optimistic locking | **Met** | `optimisticLockingPreventsLostUpdates` |
| Transactional outbox + poller | **Met** | Atomic write proven; `SKIP LOCKED` claim; publish-once proven |
| Testcontainers prove atomicity | **Met** | Real PostgreSQL 16 |
| Real event on a real topic, asserted by a consumer | **Met** | Real Kafka 3.8.1, §3 |

---

## 6. Known gaps carried into Phase 5

Stated plainly rather than left to be discovered.

1. **Optimistic-lock *retry* is not implemented.** The gate says "optimistic-lock retry"; what
   exists is optimistic-lock *detection* — the collision is caught, surfaced as a `409`, and proven
   not to lose an update. ADR-0006 also specifies bounded retry with exponential backoff and full
   jitter before surfacing. **That retry wrapper is not written**, because nothing in the write path
   yet performs the read-modify-write cycle that needs it — the submit path only ever inserts. It
   lands in Phase 5 with the first status transitions, where it can be tested against a real
   contended update rather than a contrived one.
2. **The v1→v2 upcaster is still not implemented** (carried from Phase 3). The producer emits v2;
   there is no v1 consumer yet to upcast for. Phase 5.
3. **No runtime schema validation** on publish. Build-time only. Phase 5 with `common-kafka`.
4. **`common-kafka` is still empty.** The publisher currently lives in `transaction-service`. It
   moves to the shared module in Phase 5, when a second service needs it — extracting it now would
   be speculative generality.
5. **No service-level ArchUnit rules yet** (controller→repository, cross-service imports). Phase 5,
   once there are two services to constrain.
6. **No Dockerfile**, so `transaction-service` still does not join the `core` compose profile. Phase
   13 for hardened images; the profile gate is re-checked then.
7. **The transaction never leaves `RECEIVED`.** Nothing consumes the event yet. The state machine is
   implemented and its illegal transitions throw, but only the entry state is exercised in
   production code. Phase 5.
8. **No security.** The endpoint is unauthenticated. Phase 8.

## 7. Entry conditions for Phase 5

- [x] Events are published to a real topic with a stable envelope and aggregate-keyed partitioning
- [x] The write path is proven atomic and idempotent under concurrency
- [x] Schema, migrations, and the fix-forward discipline are exercised
- [x] Phase 4 committed and pushed
