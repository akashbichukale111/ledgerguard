-- ---------------------------------------------------------------------------
-- V1: the reliability primitives the write path depends on.
--
-- Domain tables (transaction, ledger_entry) arrive in V2 with Phase 4. These
-- three come first because every other guarantee in docs/reliability.md rests
-- on them, and because they are cross-cutting: the same shapes appear in
-- reconciliation-service.
--
-- Flyway migrations are IMMUTABLE once committed. Fix forward with a new
-- version; never edit this file.
-- ---------------------------------------------------------------------------

-- --- Transactional outbox (ADR-0005) ---------------------------------------
-- Written in the SAME local transaction as the aggregate change. That single
-- fact is what eliminates the dual-write problem.
CREATE TABLE outbox_record (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,
    aggregate_type   VARCHAR(64)  NOT NULL,
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(128) NOT NULL,
    event_version    INTEGER      NOT NULL,
    payload          JSONB        NOT NULL,
    headers          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    publish_attempts INTEGER      NOT NULL DEFAULT 0
);

-- The poller's claim query is:
--   WHERE published_at IS NULL ORDER BY id FOR UPDATE SKIP LOCKED
-- A partial index keeps it cheap even when the table holds millions of already
-- published rows awaiting pruning.
CREATE INDEX ix_outbox_unpublished
    ON outbox_record (id)
    WHERE published_at IS NULL;

-- Supports the retention job and the outbox-lag metric (oldest unpublished row).
CREATE INDEX ix_outbox_published_at ON outbox_record (published_at);

COMMENT ON TABLE outbox_record IS
    'Transactional outbox. At-least-once publication: a crash between the Kafka '
    'ack and the published_at update causes republication, which is safe because '
    'consumers are idempotent. See ADR-0005 and ADR-0006.';

-- --- API-level idempotency (ADR-0006, layer 1) ------------------------------
-- Correctness rests on the UNIQUE constraint, not on application logic. Two
-- concurrent requests with the same key: one inserts, the other gets a
-- constraint violation and returns the stored response.
CREATE TABLE idempotency_record (
    idempotency_key   VARCHAR(255) PRIMARY KEY,
    endpoint          VARCHAR(255) NOT NULL,
    request_body_hash CHAR(64)     NOT NULL,
    response_status   INTEGER,
    response_body     JSONB,
    state             VARCHAR(32)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT ck_idempotency_state
        CHECK (state IN ('IN_FLIGHT', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_idempotency_created_at ON idempotency_record (created_at);

COMMENT ON COLUMN idempotency_record.request_body_hash IS
    'SHA-256 of the canonicalised request body. Same key + different body is a '
    '409 Conflict, never a silent replay of a different request.';

-- --- Consumer-level idempotency (ADR-0006, layer 2) -------------------------
-- Written in the SAME transaction as the projection or state change. Writing it
-- separately re-opens exactly the window it exists to close.
CREATE TABLE processed_event (
    consumer_group VARCHAR(128) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

COMMENT ON TABLE processed_event IS
    'Consumer dedupe. Never Redis, never in-memory: both lose their contents on '
    'restart, which is precisely when redelivery happens.';

-- --- Least-privilege grants -------------------------------------------------
GRANT USAGE ON SCHEMA public TO lg_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO lg_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lg_app;
