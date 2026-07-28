# Event catalog

> **Status.** Populated in Phase 3 alongside the `contracts` module.
>
> **This file is verified by a test, not maintained by hope.** An event type that exists in code
> but has no entry here **fails the build** ([ADR-0007](adr/0007-schema-in-repo.md)). Documentation
> that can silently drift is documentation nobody trusts.

## The event envelope

Every Kafka message is wrapped in a versioned envelope. Schema:
`libs/contracts/src/main/resources/schemas/envelope/v1.json`.

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUIDv7 | Unique per event. The consumer dedupe key. |
| `eventType` | string | e.g. `TransactionReceived` |
| `eventVersion` | int | Incremented on a breaking payload change |
| `aggregateType` | string | e.g. `Transaction` |
| `aggregateId` | UUIDv7 | **The Kafka partition key** — this is what gives per-aggregate ordering |
| `sequenceNumber` | long | Position within the aggregate's stream |
| `occurredAt` | Instant (UTC) | Business/event time |
| `recordedAt` | Instant (UTC) | Persistence time |
| `correlationId` | UUIDv7 | One business flow, end to end |
| `causationId` | UUIDv7 | The event or command that directly caused this one |
| `actor` | object | `subject`, `role`, `source` |
| `traceparent` | string | W3C trace context, propagated across the broker hop |
| `schemaRef` | string | Path to the JSON Schema that validates `payload` |
| `payload` | object | Event-specific body |

## Topics

| Topic | Producer | Consumers |
|---|---|---|
| `transactions.events.v1` | transaction-service | reconciliation-service, query-service |
| `reconciliation.events.v1` | reconciliation-service | query-service |
| `saga.commands.v1` | reconciliation-service | reconciliation-service |
| `audit.events.v1` | all services | query-service |

Each has `<topic>.retry.1|2|3` and `<topic>.dlt` companions
([ADR-0012](adr/0012-non-blocking-retry-topics.md)).

## Event types

> This section is **verified by a test**. `EventCatalogCompletenessTest` fails the build if an event
> type has a schema but no entry here, or if the entry does not state the current version.

### `TransactionReceived`

**Current version: v2** · Producer: `transaction-service` · Consumers: `reconciliation-service`,
`query-service` · Topic: `transactions.events.v1`

Emitted via the transactional outbox once a transaction instruction has been accepted and durably
persisted. This is the root of most business flows, so its `causationId` is typically null and its
`correlationId` is the one minted at the gateway.

| Field | Type | v1 | v2 | Notes |
|---|---|---|---|---|
| `transactionId` | uuid | required | required | UUIDv7; also the Kafka partition key |
| `reference` | string(1..140) | required | required | Counterparty reference. Normalised for matching, never mutated in place |
| `amount` | **string** | required | required | Decimal string, never a JSON number — see [ADR-0009](adr/0009-monetary-representation.md) |
| `currency` | string `^[A-Z]{3}$` | required | required | ISO 4217 |
| `valueDate` | date | required | required | |
| `counterpartyId` | string | required | required | Redacted in logs |
| `direction` | enum `DEBIT`\|`CREDIT` | required | required | |
| `postingDate` | date \| null | — | **added, optional** | |
| `settlementSystem` | string \| null | — | **added, optional** | |

Schemas: `schemas/TransactionReceived/v1.json`, `schemas/TransactionReceived/v2.json`

#### v1 → v2 upcaster

Required by §2.4 so the mechanism is demonstrated rather than described. Applied on read, so a
consumer only ever sees the current internal shape:

| v2 field | Value when upcasting from v1 | Why |
|---|---|---|
| `postingDate` | defaults to `valueDate` | For a v1 message the two were the same by construction — the distinction did not exist yet |
| `settlementSystem` | `null` | The information was genuinely not captured. Inventing a plausible default would be worse than an honest null, because downstream logic could not tell the difference |

**Why v2 is backward compatible:** both new fields are optional and nullable, and no existing field
was removed, narrowed, or promoted to required. A v1 producer's message still validates against v2,
and a consumer written against v1 still finds every field it knows. `SchemaCompatibilityTest`
asserts exactly this.

## Compatibility policy

Schemas are **immutable once committed**. A change means a new version file, never an edit.

CI asserts new versions only add optional fields or widen types. Removing a field, making an
optional field required, or narrowing a type is breaking and requires a new `eventVersion` plus a
documented dual-publish or upcasting path.
