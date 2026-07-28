// ---------------------------------------------------------------------------
// MongoDB initialiser. Runs ONCE, on first container start.
//
// MongoDB has no Flyway, so this file carries the same discipline by hand:
// every change is additive, versioned by the SCHEMA_VERSION marker below, and
// idempotent — createCollection and createIndex are no-ops when the target
// already exists, so re-running is safe.
//
// See docs/operations.md for the migration procedure.
// ---------------------------------------------------------------------------

const SCHEMA_VERSION = 1;


// getSiblingDB reuses the CURRENT authenticated session. `new Mongo()` would
// open a fresh unauthenticated connection, which works only inside the initdb
// entrypoint and fails on any manual re-run — defeating the idempotency this
// file claims.
const target = db.getSiblingDB(process.env.MONGO_DB || "ledgerguard_read");

// createCollection throws NamespaceExists (48) on re-run; createIndex is a
// genuine no-op when an identical index already exists.
function createIfAbsent(name) {
  try { target.createCollection(name); }
  catch (e) { if (e.code !== 48) { throw e; } }
}

// --- Read-model projections -------------------------------------------------

createIfAbsent("transaction_360");
target.transaction_360.createIndex({ transactionId: 1 }, { unique: true, name: "ux_txn360_transactionId" });
target.transaction_360.createIndex({ correlationId: 1 }, { name: "ix_txn360_correlationId" });
// Supports the keyset-paginated grid: sort key + unique tiebreaker (ADR-0008).
target.transaction_360.createIndex({ occurredAt: -1, transactionId: -1 }, { name: "ix_txn360_keyset" });

createIfAbsent("reconciliation_grid");
target.reconciliation_grid.createIndex({ caseId: 1 }, { unique: true, name: "ux_grid_caseId" });
target.reconciliation_grid.createIndex({ status: 1, occurredAt: -1, caseId: -1 }, { name: "ix_grid_status_keyset" });
target.reconciliation_grid.createIndex({ classification: 1, occurredAt: -1, caseId: -1 }, { name: "ix_grid_classification_keyset" });
target.reconciliation_grid.createIndex({ currency: 1, occurredAt: -1, caseId: -1 }, { name: "ix_grid_currency_keyset" });
target.reconciliation_grid.createIndex({ assignee: 1, occurredAt: -1, caseId: -1 }, { name: "ix_grid_assignee_keyset" });

// --- Consumer-side idempotency ---------------------------------------------
// The authoritative dedupe record for Mongo-backed projections. Written in the
// same transaction as the projection update (ADR-0006). Never Redis.
createIfAbsent("processed_event");
target.processed_event.createIndex(
  { consumerGroup: 1, eventId: 1 },
  { unique: true, name: "ux_processed_group_event" }
);

// --- Schema version marker --------------------------------------------------
createIfAbsent("schema_version");
target.schema_version.updateOne(
  { _id: "current" },
  { $set: { version: SCHEMA_VERSION, appliedAt: new Date() } },
  { upsert: true }
);

print("ledgerguard: mongo schema initialised at version " + SCHEMA_VERSION);
