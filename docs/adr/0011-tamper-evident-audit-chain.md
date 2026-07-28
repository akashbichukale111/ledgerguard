# 0011. Hand-rolled hash-chained audit log — tamper-evident, not tamper-proof

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

Financial systems must prove what happened and who did it. Months later an auditor asks: was this
record altered after it was written?

An ordinary audit table cannot answer that question. Anyone with `UPDATE` on the table can change
a row and nobody can tell. The requirement is **detectability of modification**.

The word chosen to describe the result matters enormously, and most projects overclaim it.

## Decision drivers

- Modification of a written record must be **detectable**.
- Detection must identify *where* the chain broke, not merely that something is wrong.
- No external dependency, no cost, no blockchain.
- The claim in the README must be exactly what the mechanism delivers.

## Considered options

1. **Plain append-only table** with database grants.
2. **Hash-chained records** with grants (chosen).
3. **Cryptographically signed records** (each entry signed with a private key).
4. **External immutable store** — WORM storage, QLDB, or a blockchain.

## Decision outcome

**Chosen: option 2 — SHA-256 hash chain, plus database grant enforcement.**

Each record stores:

```
previousHash  = the recordHash of the immediately preceding record
recordHash    = SHA-256( canonical(record) || previousHash )
```

`canonical(record)` is a deterministic serialisation — field order fixed, no whitespace variation,
timestamps in a single normalised format. **If canonicalisation is not deterministic, verification
produces false positives and the entire mechanism is worthless.** It is therefore tested directly.

Changing any field of record *N* changes its `recordHash`, which no longer matches the
`previousHash` stored in record *N+1*. Verification walks the chain from the genesis record and
reports the **first index** where the link breaks.

Additional enforcement:

- The application role has **no `UPDATE` or `DELETE` grant** on the audit table. This is a
  `REVOKE` in a Flyway migration, not application-layer discipline — application discipline is
  bypassed by the next developer who writes a repository method.
- A verification endpoint and CLI walk the chain and report the first break.
- §9 requires a test that **tampers with a record directly in the database** and asserts
  verification detects it at the correct index.

### The honesty clause — this is tamper-EVIDENT, not tamper-PROOF

**An attacker with write access to the entire table can recompute the whole chain from the point
of modification forward, and verification will pass.** The hash chain makes *undetected* tampering
require rewriting every subsequent record, rather than editing one row. That raises the cost and
narrows who can do it. It does not make it impossible.

This is stated here, in `docs/security.md`, in the README, and in the UI's verification result.
Any documentation in this repository that implies otherwise is a defect.

What would actually close the gap:
- **Periodically publishing the current head hash to an external, independently-controlled
  location** (a notarisation service, a second organisation's system, or simply an append-only log
  on different infrastructure with different credentials). An attacker would then also need to
  compromise that. **This is not implemented**, and it is the single most valuable hardening step
  available.
- Write-once storage that physically refuses modification.

### Consequences

**Positive**

- Silent single-record modification becomes detectable, with the position identified.
- Zero external dependencies; the mechanism is a few dozen lines and fully readable.
- Database grants provide defence in depth independent of application code.

**Negative**

- **Writes must be serialised.** Each record needs the immediately-preceding hash, so concurrent
  appends must be ordered. This is a throughput ceiling on the audit path and the direct reason
  the chain lives in PostgreSQL (ADR-0014) rather than MongoDB.
- Verification is **O(n)** — a full walk of the chain. For a large audit log this is a slow
  operation and cannot be run on every request. Segment-level checkpointing would help and is not
  implemented.
- A legitimate data-correction need (GDPR erasure, a genuine mistake) is **architecturally
  awkward by design**. Correction means appending a compensating record, never editing. That is
  the correct behaviour for an audit log and a genuine operational constraint.
- Canonicalisation is a permanent compatibility surface: changing how records are serialised
  invalidates every historical hash. The format is therefore versioned.

## Pros and cons of the options

### Option 1 — plain append-only table + grants

- Good: simple; grants are real enforcement against the application role.
- Bad: a DBA, a compromised superuser, or anyone with direct database access modifies a row and
  **nothing detects it**. Grants control who may write; they leave no evidence when bypassed.

### Option 2 — hash chain + grants (chosen)

- Good: detection with position; no dependencies.
- Bad: serialised writes; O(n) verification; not proof against whole-table rewrite.

### Option 3 — signed records

- Good: proves *authorship*, not just integrity — a stronger property. An attacker without the
  private key cannot forge a valid record even with full table access.
- Bad: introduces key management — storage, rotation, and the question of what a signature from a
  key that has since been rotated means years later.
- Bad: signing per record is meaningfully more expensive than hashing.
- **Verdict: strictly stronger and the natural next step. Rejected for v1 because key management
  done badly is worse than no signatures, and doing it properly is a project of its own.**
  Combining it with chaining would be the ideal end state.

### Option 4 — external immutable store / blockchain

- Good: WORM storage or QLDB genuinely removes the whole-table-rewrite attack.
- Bad: a managed service — contradicts the zero-external-dependency constraint (§6) and costs money.
- Bad: a blockchain here would be pure theatre. The property needed is an append-only log with
  independent control, which is achieved by the notarisation approach above at a fraction of the
  complexity. Distributed consensus solves a trust problem this system does not have.

## More information

- Why PostgreSQL hosts the chain: `docs/adr/0014-audit-chain-in-postgresql.md`
- Threat model and accepted risks: `docs/security.md`
- Chain-break runbook: `docs/failure-modes.md`
