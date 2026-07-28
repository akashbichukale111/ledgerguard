-- ---------------------------------------------------------------------------
-- V1: the hash-chained audit log.
--
-- This table is why the audit chain lives in PostgreSQL rather than MongoDB
-- (ADR-0014). Three properties are enforced by the DATABASE here, not by
-- application code:
--
--   1. a total order            -> chain_index, GENERATED ALWAYS AS IDENTITY
--   2. no forks in the chain    -> UNIQUE on chain_index and on previous_hash
--   3. no rewriting of history  -> REVOKE UPDATE, DELETE from the app role
--
-- For the one component whose entire value is being trustworthy, having the
-- database enforce these beats having our code enforce them.
--
-- Flyway migrations are IMMUTABLE once committed. Fix forward.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_event (
    chain_index    BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    event_id       UUID         NOT NULL UNIQUE,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    correlation_id UUID         NOT NULL,
    causation_id   UUID,

    actor_subject  VARCHAR(255) NOT NULL,
    actor_role     VARCHAR(64)  NOT NULL,
    actor_source   VARCHAR(64)  NOT NULL,

    service        VARCHAR(64)  NOT NULL,
    action         VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64),
    aggregate_id   UUID,
    outcome        VARCHAR(32)  NOT NULL,

    -- Redacted before storage. Account numbers and counterparty names are
    -- masked to their last 4 characters; see docs/security.md.
    before_state   JSONB,
    after_state    JSONB,

    -- --- the chain ---------------------------------------------------------
    -- previous_hash is NULL only for the genesis record. The partial unique
    -- index below enforces that there is at most one genesis.
    previous_hash  CHAR(64),
    record_hash    CHAR(64)     NOT NULL UNIQUE,

    -- Canonicalisation format version. Changing how records are serialised
    -- invalidates every historical hash, so the format is versioned rather
    -- than assumed stable forever.
    hash_format    SMALLINT     NOT NULL DEFAULT 1,

    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

-- A fork means two records claiming the same predecessor. Without this, a fork
-- produces two individually-valid-looking chains and verification silently
-- follows one of them.
CREATE UNIQUE INDEX ux_audit_previous_hash
    ON audit_event (previous_hash)
    WHERE previous_hash IS NOT NULL;

-- At most one genesis record.
CREATE UNIQUE INDEX ux_audit_genesis
    ON audit_event ((previous_hash IS NULL))
    WHERE previous_hash IS NULL;

CREATE INDEX ix_audit_correlation ON audit_event (correlation_id, chain_index);
CREATE INDEX ix_audit_occurred_at ON audit_event (occurred_at DESC, chain_index DESC);
CREATE INDEX ix_audit_actor       ON audit_event (actor_subject, occurred_at DESC);
CREATE INDEX ix_audit_aggregate   ON audit_event (aggregate_type, aggregate_id);

COMMENT ON TABLE audit_event IS
    'Append-only, hash-chained audit log. TAMPER-EVIDENT, NOT TAMPER-PROOF: an '
    'attacker with write access to this whole table can recompute the chain from '
    'the point of modification forward and pass verification. See ADR-0011.';

COMMENT ON COLUMN audit_event.record_hash IS
    'SHA-256( canonical(record) || previous_hash ). Canonicalisation must be '
    'deterministic or verification produces false positives.';

-- ---------------------------------------------------------------------------
-- Append-only enforcement.
--
-- The application role may INSERT and SELECT. It may not UPDATE or DELETE.
-- This is the §6 requirement, expressed where it cannot be bypassed by the next
-- developer who writes a repository method.
--
-- Honest scope: this constrains lg_app. A superuser, or anyone with direct
-- database access, is unaffected — which is exactly why the hash chain exists
-- as a second, independent layer.
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO lg_app;
GRANT SELECT, INSERT ON audit_event TO lg_app;
REVOKE UPDATE, DELETE ON audit_event FROM lg_app;

-- Future tables in this database default to the same posture rather than
-- relying on someone remembering to revoke.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT ON TABLES TO lg_app;
