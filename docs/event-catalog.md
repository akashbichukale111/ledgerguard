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

*Populated in Phase 3. Each entry will carry: type, current version, producer, consumers, schema
path, payload fields, and any upcasters. At least one real v1 to v2 upcast is required by §2.4 so
the mechanism is demonstrated rather than described.*

## Compatibility policy

Schemas are **immutable once committed**. A change means a new version file, never an edit.

CI asserts new versions only add optional fields or widen types. Removing a field, making an
optional field required, or narrowing a type is breaking and requires a new `eventVersion` plus a
documented dual-publish or upcasting path.
