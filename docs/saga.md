# Saga orchestration

> **Status.** Mechanics and sequences are specified and diagrammed as of Phase 1. Implemented and
> tested in Phase 5. The acceptance evidence — saga success, step failure → compensation,
> compensation failure, and timeout via fixed clock — is recorded in `docs/phase-reports/phase-05.md`.

Reasoning for orchestration over choreography, and for hand-rolling rather than adopting Temporal,
is [ADR-0004](adr/0004-orchestrated-saga-hand-rolled.md).

## 1. Why reconciliation is a saga at all

Reconciliation spans services and has steps with side effects that must be undone if a later step
fails. There is no distributed transaction available, and XA would be the wrong tool even if there
were. So: a saga — a sequence of local transactions, each with a compensating action.

The orchestrator lives in `reconciliation-service` because that service owns the workflow's
meaning. Nothing else in the system decides what reconciliation *is*.

## 2. Persistent state

Three tables. All updated **in the same local transaction as the step's own effect** — a saga whose
state is updated separately from its work can lose track of what it did.

| Table | Grain | Why |
|---|---|---|
| `saga_instance` | One row per workflow instance | Current state, correlation IDs, timeout deadline |
| `saga_step` | One row per step **attempt** | Retries stay visible instead of collapsing into a counter |
| `compensation_record` | One row per compensation executed | Each with its own outcome |

Plus a **command outbox**: the orchestrator's outgoing commands are written in the same transaction
as the state change, so a crash between "decide" and "send" cannot lose the command.

Each step declares, as data: its forward command, its compensating command, a timeout, a retry
policy, and whether failure requires compensation.

## 3. Happy path

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant O as Saga orchestrator
    participant DB as PostgreSQL
    participant M as Matching engine
    participant Q as query-service

    K->>O: TransactionReceived
    O->>DB: INSERT saga_instance (STARTED, deadline)

    rect rgb(236, 253, 245)
    note over O,DB: Step 1 — validate entries
    O->>DB: saga_step(1, EXECUTING) + local effect<br/>ONE transaction
    O->>DB: saga_step(1, COMPLETED)
    end

    rect rgb(236, 253, 245)
    note over O,DB: Step 2 — open reconciliation case
    O->>DB: saga_step(2, EXECUTING)
    O->>DB: append CaseOpened to case event stream
    O->>DB: saga_step(2, COMPLETED)
    end

    rect rgb(236, 253, 245)
    note over O,M: Step 3 — run the matching engine
    O->>DB: saga_step(3, EXECUTING)
    O->>M: match(ledgerEntries, statementEntries, ruleSetVersion)
    M-->>O: MatchResult + MatchExplanation
    O->>DB: saga_step(3, COMPLETED)
    end

    rect rgb(236, 253, 245)
    note over O,DB: Step 4 — record outcome
    O->>DB: append CaseAutoMatched + command outbox<br/>ONE transaction
    O->>DB: saga_instance -> COMPLETED
    end

    O->>K: ReconciliationCompleted (via outbox poller)
    K->>Q: project
    Q->>Q: update 360 document, push STOMP frame
```

## 4. Failure and compensation

```mermaid
sequenceDiagram
    autonumber
    participant O as Saga orchestrator
    participant DB as PostgreSQL
    participant M as Matching engine
    participant K as Kafka

    note over O: Steps 1 and 2 already COMPLETED

    O->>DB: saga_step(3, EXECUTING)
    O->>M: match(...)
    M--xO: step failure

    O->>DB: saga_step(3, FAILED) + saga_instance -> COMPENSATION_REQUIRED
    O->>DB: saga_instance -> COMPENSATING

    rect rgb(254, 242, 242)
    note over O,DB: Compensate in REVERSE order
    O->>DB: compensate step 2 — close the case as abandoned
    O->>DB: compensation_record(2, SUCCEEDED)
    O->>DB: compensate step 1 — release the entry reservations
    O->>DB: compensation_record(1, SUCCEEDED)
    end

    alt all compensations succeeded
        O->>DB: saga_instance -> COMPENSATED
        O->>K: SagaCompensated
    else a compensation itself failed
        O->>DB: compensation_record(n, FAILED)
        O->>DB: saga_instance -> COMPENSATION_FAILED
        O->>K: SagaCompensationFailed
        note over O,K: TERMINAL. Alertable. Visible in the<br/>Saga Control Center. A human must act.
    end
```

Three properties that must hold, and are tested rather than assumed:

1. **Compensations are idempotent.** They run under the same at-least-once delivery as everything
   else, so running one twice must be harmless.
2. **Compensation runs in reverse order.** Step 3's effects are undone before step 2's. This holds
   because the code says so, not because a framework guarantees it — which is precisely why it is
   tested.
3. **Compensation failure is terminal and loud.** `COMPENSATION_FAILED` is never swallowed. The
   system has failed to undo its own partial work, and pretending otherwise leaves silent
   corruption.

## 5. Timeouts

A scheduled sweeper finds instances past their deadline and drives them into
`TIMED_OUT → COMPENSATION_REQUIRED`.

The sweeper reads time from an **injected `java.time.Clock`**. Tests advance a fixed clock rather
than calling `Thread.sleep` — a test that sleeps is slow, flaky, and proves less.

**Honest limitation:** timers are only as durable as the sweeper. If the sweeper does not run,
timeouts do not fire. Temporal's durable timers have no such single point of neglect
([ADR-0004](adr/0004-orchestrated-saga-hand-rolled.md)). The sweeper's liveness is therefore itself
a monitored metric.

## 6. Deliberate failure injection — dev profile only

Demo scenario 6 (§13) requires triggering a compensation on demand. Two mechanisms, both
**structurally impossible to enable under the `prod` profile**:

- a `dev`-profile-only endpoint, and
- a magic reference prefix (e.g. `FAIL-STEP-3-`) recognised only when the injection bean is loaded.

The bean is annotated `@Profile("dev")`. **A test asserts the bean does not load under `prod`** —
because a comment saying "dev only" is not a control, and this is exactly the kind of hook that
ends up in production in real systems.

## 7. Known limitations

Stated here rather than discovered by a reviewer:

- **No workflow versioning.** Changing the step sequence while instances are in flight is not
  handled. Acceptable only because instances are short-lived, so a deploy drains quickly.
- **Compensation ordering is our code's responsibility**, not a framework guarantee.
- **`COMPENSATION_FAILED` has no automated escalation** beyond a metric and UI surfacing.
- **The sweeper is a single point of neglect** for all timeout behaviour.

## Related

- [ADR-0004](adr/0004-orchestrated-saga-hand-rolled.md) — orchestration and the workflow-engine rejection
- [`domain-model.md`](domain-model.md) — the saga state machine
- [`failure-modes.md`](failure-modes.md) — compensation-failure runbook
