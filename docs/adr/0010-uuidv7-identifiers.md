# 0010. UUIDv7 for identifiers, not UUIDv4 or database sequences

- **Status:** Accepted
- **Date:** 2026-07-28

## Context and problem statement

`transactionId`, `caseId`, `sagaId`, and `eventId` need to be unique across services without
coordination, because they are minted before anything reaches a database and must be usable as a
Kafka partition key.

## Decision drivers

- Generated client-side / service-side without a round trip.
- Must not degrade index performance as volume grows.
- Must not leak business information (volume, sequence) to an external party.

## Considered options

1. **Database sequence / `BIGSERIAL`.**
2. **UUIDv4** (random).
3. **UUIDv7** (time-ordered) (chosen).
4. **ULID / KSUID.**

## Decision outcome

**Chosen: option 3 — UUIDv7.**

UUIDv7 encodes a 48-bit Unix millisecond timestamp in its most significant bits, followed by
random data. It is a standard UUID (RFC 9562) so it fits `UUID` columns and `java.util.UUID`
without custom types, but it sorts chronologically.

### Why time-ordering matters — the concrete cost of UUIDv4

This is the substantive argument and the reason "just use v4" is wrong at scale.

PostgreSQL's primary key index is a B-tree. With **UUIDv4**, each new key lands at a random
position in the keyspace. Consequences:

- **Random write amplification.** Every insert dirties a different page. The working set of the
  index becomes the *whole* index rather than its right edge, so cache hit rate collapses once the
  index exceeds RAM.
- **Page splits everywhere.** Inserting into the middle of full pages splits them, producing index
  fragmentation and poor space utilisation.

With **UUIDv7**, inserts append to the right edge of the B-tree — the same access pattern as a
sequence. The hot pages stay in cache, splits are rare, and space utilisation stays high.

Secondary benefits:

- IDs sort chronologically, so `ORDER BY id` approximates `ORDER BY created_at` and pairs
  naturally with the keyset pagination tiebreaker (ADR-0008).
- Debugging is easier: the creation time is recoverable from the identifier.

### The privacy trade-off, stated

UUIDv7 **deliberately leaks a creation timestamp**. For LedgerGuard's identifiers this is
acceptable — the timestamp is already in the payload (`occurredAt`), and these IDs are not exposed
to untrusted third parties as capability tokens.

**This decision does not extend to secrets.** Session tokens, password-reset tokens, or anything
where unguessability is the security property must use a CSPRNG, not UUIDv7 — its random component
is smaller and its high bits are predictable. That is a genuine constraint on where this ADR
applies.

### Consequences

**Positive**

- Index locality comparable to a sequence, without coordination.
- Generated anywhere: gateway, service, or test, with no database round trip.
- Usable directly as a Kafka partition key.
- Chronologically sortable, which simplifies pagination and debugging.

**Negative**

- 16 bytes vs 8 for a `BIGINT` — larger indexes and larger foreign keys throughout.
- Leaks creation time (above).
- Java 21's `java.util.UUID` has **no built-in v7 generator**, so generation is either a small
  dependency or ~20 lines of our own code. Either way it must be correct — a subtly wrong
  implementation (bad bit layout, non-monotonic within a millisecond) silently destroys the
  ordering property that is the entire justification for this decision. It is therefore tested for
  monotonicity, version/variant bits, and uniqueness under concurrency.

## Pros and cons of the options

### Option 1 — database sequence

- Good: smallest (8 bytes), perfect index locality, human-readable.
- Bad: requires a database round trip before the ID exists — impossible when the ID is minted at
  the edge and used as a partition key before persistence.
- Bad: **leaks business volume.** A counterparty receiving transaction `#84213` on Monday and
  `#84890` on Tuesday knows the daily volume. This is a real information-disclosure problem for
  externally-visible identifiers.
- Bad: collides across services and environments; makes merging or sharding painful.

### Option 2 — UUIDv4

- Good: no information leakage at all; universally supported; trivially available in the JDK.
- Bad: random insert distribution degrades B-tree performance as described above.
- Bad: no natural ordering, so an explicit timestamp column and index are required for any
  chronological query.
- **Verdict: the default choice, and the one this decision consciously departs from. For a
  low-volume system it would be entirely fine.**

### Option 3 — UUIDv7 (chosen)

- Good: index locality plus decentralised generation; standard UUID representation.
- Bad: 16 bytes; leaks creation time; needs a correct generator.

### Option 4 — ULID / KSUID

- Good: same time-ordering benefit; ULID's Crockford base32 encoding is more compact and less
  error-prone to read aloud than UUID hex.
- Bad: **not a UUID.** Requires custom column types, custom serialisers, and custom handling in
  every tool that understands UUIDs natively (psql, MongoDB, JDBC drivers, Kafka key serdes).
- Bad: UUIDv7 is now a ratified standard (RFC 9562) offering the same property with none of the
  interoperability cost, which makes ULID's remaining advantage cosmetic.

## More information

- Identifier semantics and correlation/causation: `docs/domain-model.md`
- Keyset pagination tiebreaker: `docs/adr/0008-keyset-pagination.md`
