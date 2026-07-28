# 0003. Event sourcing scoped to the ReconciliationCase aggregate only

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

Event sourcing is frequently applied to an entire system on the grounds that it is "the
event-driven way". The result is that every trivial entity — a reference-data row, a
configuration flag — acquires an event stream, a rehydration cost, a snapshotting strategy, and
an upcasting burden, in exchange for an audit trail nobody reads.

The question is not *whether* to event-source, but **which aggregate's history is itself the
product**.

## Decision drivers

- An auditor will ask "why was this case matched, and what did the system know at each step?"
  For one aggregate, that question is the entire point of the system.
- Event sourcing has real, permanent costs: rehydration latency, snapshot management, schema
  evolution on stored events forever, and a much harder "just fix the data" story.
- A reviewer should see that the boundary was chosen deliberately, not applied uniformly.

## Considered options

1. **Event-source everything.**
2. **Event-source nothing** — state-stored aggregates with a domain-event outbox.
3. **Event-source `ReconciliationCase` only; state-store everything else** (chosen).

## Decision outcome

**Chosen: option 3.**

`ReconciliationCase` is event-sourced:
- its event stream is the **source of truth**,
- current state is derived by folding the stream,
- a materialised current-state row exists purely as a read optimisation and **can be dropped and
  rebuilt** from the stream at any time.

Everything else — `Transaction`, `LedgerEntry`, `ExternalStatementEntry`, `SagaInstance`,
`IdempotencyRecord`, `OutboxRecord` — is **state-stored** with a transactional outbox emitting
domain events.

### Why `ReconciliationCase` specifically

Its history *is* the product. A case is opened, candidates are evaluated, a rule fires, a break is
raised, an analyst claims it, investigates, force-matches with a justification, and closes it.
Two years later a regulator asks why. With event sourcing that question is answered by replaying
the stream under the rule-set version recorded on each event. With a state-stored row plus an
audit table, it is answered by trusting that the audit table was written correctly and completely
at every mutation site — a much weaker guarantee, and one that decays as code changes.

### Why nothing else

`Transaction` has a lifecycle, but its history is *reporting*, not *product*. Nobody replays a
transaction to understand it; they look at its current state and its audit entries. The outbox
already gives us a durable, ordered record of every state change for projection purposes. Adding
event sourcing would buy rehydration cost and schema-evolution burden for no additional answer to
any question a user actually asks.

### Consequences

**Positive**

- The aggregate whose history matters gets a real, replayable history.
- Rule-set versioning is meaningful: a case can be explained under the rules that existed when it
  was decided.
- The current-state row is disposable, which makes projection bugs recoverable rather than
  data-loss events.

**Negative**

- **Two persistence idioms in one codebase.** A developer must know which aggregate follows which
  rule. This is a genuine cognitive cost and the main argument against this decision. It is
  mitigated by keeping the event-sourced aggregate in one clearly-named package and by an ArchUnit
  rule preventing direct state mutation of `ReconciliationCase` outside its command handlers.
- Events for `ReconciliationCase` are stored forever and must be readable forever. Every schema
  change to them needs an upcaster (`docs/adr/0007-schema-in-repo.md`). "Just run a migration" is
  not available.
- Rehydration cost grows with stream length. Bounded here because a case's lifetime is short
  (open → resolved), so streams stay in the tens of events. **If cases were long-lived, snapshotting
  would be mandatory; it is deliberately not implemented, and that is a scale limit, not an
  oversight.**

## Pros and cons of the options

### Option 1 — event-source everything

- Good: uniform mental model; one idiom to learn.
- Bad: every aggregate pays rehydration and upcasting cost.
- Bad: `IdempotencyRecord` and `OutboxRecord` as event streams is close to absurd — they are
  infrastructure ledgers whose entire value is a unique constraint and a cheap lookup.
- Bad: makes the system slower and harder to operate with no additional answered question.

### Option 2 — event-source nothing

- Good: simplest; one idiom; easiest to onboard.
- Good: entirely sufficient for the *functional* requirements.
- Bad: the "explain this decision under the rules of the time" capability degrades to "trust the
  audit table", which is exactly the guarantee a financial auditor will probe hardest.

### Option 3 — scoped (chosen)

- Good: the cost is paid exactly where the benefit lands.
- Good: demonstrates that the boundary was reasoned about — which is more informative to a
  reviewer than either uniform answer.
- Bad: two idioms, as stated above.

## More information

- Case state machine: `docs/domain-model.md`
- Rule-set versioning and explainability: `docs/reconciliation-engine.md`
- Outbox used by the state-stored aggregates: `docs/adr/0005-transactional-outbox.md`
