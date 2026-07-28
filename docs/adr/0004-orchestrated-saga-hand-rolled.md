# 0004. Orchestrated saga, hand-rolled, rejecting workflow engines

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

Reconciliation is a multi-step workflow that spans services and can fail partway through:
validate → reserve/mark entries → run matching → record outcome → raise exception or close.
Steps have side effects that must be undone if a later step fails. A distributed transaction is
not available (and would be the wrong tool), so this is a saga.

Two questions follow: **choreography or orchestration**, and **build or adopt**.

## Decision drivers

- The workflow's state must be inspectable — "Saga Control Center" (§8.3) is a required screen,
  which means the state machine must exist as *data* somewhere queryable.
- Compensation must be provable by tests, not assumed.
- The purpose of this repository is to demonstrate understanding of the mechanism.
- The resource budget forbids another heavyweight container.

## Considered options

**Coordination style**
1. Choreography — services react to each other's events; no central coordinator.
2. Orchestration — one service owns the state machine and issues commands (chosen).

**Implementation**
- A. Hand-rolled persisted state machine (chosen).
- B. Temporal / Cadence.
- C. Camunda / Zeebe (BPMN).
- D. Axon Framework.
- E. Spring StateMachine.

## Decision outcome

**Chosen: orchestration (option 2), hand-rolled (option A).**

The orchestrator lives in `reconciliation-service` and consists of:

- `SagaInstance` — one row per workflow instance, holding current state, correlation identifiers,
  and a timeout deadline.
- `SagaStep` — one row per step *attempt*, so retries are visible rather than collapsed.
- `CompensationRecord` — one row per compensation executed, with its outcome.
- An **explicit transition table** — legal transitions are declared as data; an illegal transition
  throws a typed domain exception rather than silently corrupting state.
- A **command outbox** — the orchestrator's outgoing commands are written in the same local
  transaction as the state change, so a crash between "decide" and "send" cannot lose the command.
- A **scheduled sweeper** that finds instances past their deadline and triggers compensation,
  driven by an injected `Clock` so it is testable without `Thread.sleep`.

### Why orchestration over choreography

Choreography scatters the state machine across every participating service. No single place
answers "what state is this workflow in and why did it stop?" — you reconstruct it by correlating
logs across services. That makes the Saga Control Center screen impossible to build honestly, and
it makes compensation ordering an emergent property rather than a designed one.

Choreography's real advantage — looser coupling between services — matters most when teams deploy
independently at different cadences. That is not this system's constraint.

### Why hand-rolled over a workflow engine

The point of this repository is to demonstrate that the mechanism is understood. Importing
Temporal reduces the saga to a set of annotations and moves every interesting property — durable
timers, retry semantics, compensation ordering, exactly-what-happens-on-crash — into a black box
the author never had to reason about. A reviewer learns nothing about the author from
`@SagaOrchestrationStart`.

**What production would do instead, stated honestly:** for a real system with many workflow types
and a team maintaining them, **Temporal is very likely the right answer.** It provides durable
timers, deterministic replay, versioning of running workflows, and a mature operations UI — all of
which this hand-rolled orchestrator either lacks or implements naively. The costs that make it
wrong *here* are: another stateful cluster (server + its own datastore) against a 10 GB budget,
and the fact that it hides exactly what this project exists to show.

### Consequences

**Positive**

- Saga state is queryable SQL, so the Control Center screen is backed by real data.
- Every retry attempt and compensation is a row, making the audit story concrete.
- Timeout behaviour is testable with a fixed clock.
- No additional container.

**Negative — the parts a workflow engine would have handled**

- **No workflow versioning.** Changing the step sequence while instances are in flight is not
  solved. Currently the mitigation is that instances are short-lived, so a deploy drains quickly.
  A long-running workflow would break here.
- **Timers are only as durable as the sweeper.** If the sweeper does not run, timeouts do not
  fire. Temporal's durable timers have no such single point of neglect.
- **Compensation ordering is our responsibility.** Steps compensate in reverse order because the
  code says so, not because a framework guarantees it. This is covered by tests, which is the only
  reason it is acceptable.
- **`COMPENSATION_FAILED` is terminal and needs a human.** There is no automatic escalation beyond
  surfacing it in the UI and a metric. This is a deliberate stopping point, not a gap to be
  discovered later.

## Pros and cons of the options

### B — Temporal

- Good: durable timers, replay determinism, workflow versioning, mature UI, battle-tested.
- Bad: server + datastore against a tight RAM budget.
- Bad: hides the mechanism this project is meant to demonstrate.
- **Verdict: the right production choice; wrong here, for stated reasons.**

### C — Camunda / Zeebe (BPMN)

- Good: business-readable diagrams; strong when non-engineers own the process definition.
- Bad: BPMN's value appears when business analysts edit workflows. There are no business analysts
  here; the process is defined by engineers in code.
- Bad: heavyweight for one workflow type.

### D — Axon Framework

- Good: integrated event sourcing + saga + CQRS.
- Bad: opinionated to the point that the architecture becomes Axon's rather than ours, which makes
  every other ADR in this directory moot.
- Bad: this project deliberately event-sources exactly one aggregate (ADR-0003); Axon pushes hard
  toward event-sourcing everything.

### E — Spring StateMachine

- Good: lightweight, in-process, no new container.
- Bad: it is a state machine, not a saga framework — no persistence story, no compensation model,
  no timeout handling. We would end up writing the same tables anyway, plus a dependency.
- **This is the closest rejected option:** its rejection is about it solving the easy half.

## More information

- Saga mechanics and the transition table: `docs/saga.md`
- Failure injection for demos (dev-profile only): `docs/demo.md`
- Compensation failure runbook: `docs/failure-modes.md`
