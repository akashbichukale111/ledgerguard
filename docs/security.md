# Security

> **Status.** Populated in Phase 8. The role model and the authorization-matrix approach below are
> decided as of Phase 1; the STRIDE threat model and the matrix itself require the endpoints to
> exist, so they are written against real code rather than in advance.

## Roles and separation of duties

| Role | May | May NOT |
|---|---|---|
| `AUDITOR` | Read everything, including the audit trail; verify chain integrity | **Mutate anything, ever** |
| `ANALYST` | Investigate, resolve, force-match up to an amount cap | Operate the system (no DLQ replay, no saga retry) |
| `OPERATIONS` | DLQ replay, saga retry, system operations | **Resolve financial breaks** — no resolution authority |
| `ADMIN` | Rule-set and configuration management | — |

The `ANALYST` / `OPERATIONS` split is deliberate separation of duties: the person who can replay
messages into the system is not the person who can decide a financial outcome.

## Authorization matrix

*Populated in Phase 8 as a table of endpoint × role × expected status.*

Backed by a **parameterised test iterating every endpoint × every role**, asserting 200/403/401.
**A new endpoint without a matrix entry fails the build** — this is the mechanism that stops the
matrix from rotting.

## Planned contents (Phase 8)

- OAuth2 resource-server configuration; JWKS validation with issuer, audience, expiry, and
  algorithm pinned (reject `none`, reject `HS256` where `RS256` is expected)
- Gateway security headers: CSP without `unsafe-inline`, HSTS, `X-Content-Type-Options`,
  `Referrer-Policy`, frame-ancestors; CORS pinned to known origins; request size limits
- WebSocket security: JWT on STOMP `CONNECT`, per-destination subscription authorization,
  termination on token expiry
- PII redaction: `@Redacted`, a Jackson serializer, and a Logback masking converter. Account
  numbers, counterparty names, and references show last 4 characters only. Correlation IDs and
  transaction IDs are safe to log; counterparty business identifiers are not.
- Audit log grants: `REVOKE UPDATE, DELETE` on the audit table in a Flyway migration
- STRIDE threat model against the real data flows, with mitigations mapped to file and class
- Accepted risks / out of scope for a portfolio deployment

## Stated up front

The audit chain is **tamper-evident, not tamper-proof**. An attacker with write access to the whole
audit table can recompute the chain and pass verification. The mitigation that would close this —
periodically publishing the head hash to independently-controlled storage — is **not implemented**.
See [ADR-0011](adr/0011-tamper-evident-audit-chain.md).
