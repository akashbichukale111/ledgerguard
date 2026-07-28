-- ---------------------------------------------------------------------------
-- V1: saga orchestration state, consumer dedupe, and reconciliation outcomes.
--
-- The saga's state is DATA, not code. That is what makes the Saga Control
-- Center screen possible and what lets a human answer "what state is this
-- workflow in and why did it stop?" without correlating logs. See ADR-0004.
-- ---------------------------------------------------------------------------

CREATE TABLE saga_instance (
    id              UUID         PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,

    saga_type       VARCHAR(64)  NOT NULL,
    transaction_id  UUID         NOT NULL,
    correlation_id  UUID         NOT NULL,

    state           VARCHAR(32)  NOT NULL,
    current_step    INTEGER      NOT NULL DEFAULT 0,

    started_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    -- The sweeper finds instances past this. Timeouts are only as durable as the
    -- sweeper actually running; that is a stated limitation of hand-rolling the
    -- orchestrator rather than adopting durable timers (ADR-0004).
    deadline_at     TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,

    failure_reason  TEXT,

    CONSTRAINT ck_saga_state CHECK (state IN (
        'STARTED', 'STEP_EXECUTING', 'STEP_COMPLETED', 'COMPLETED',
        'COMPENSATION_REQUIRED', 'COMPENSATING', 'COMPENSATED',
        'COMPENSATION_FAILED', 'TIMED_OUT'))
);

-- One saga per transaction: a duplicate TransactionReceived must not start a
-- second workflow. This constraint is the dedupe of last resort even if the
-- processed_event check were bypassed.
CREATE UNIQUE INDEX ux_saga_transaction ON saga_instance (transaction_id);
CREATE INDEX ix_saga_state    ON saga_instance (state, deadline_at);
CREATE INDEX ix_saga_deadline ON saga_instance (deadline_at) WHERE completed_at IS NULL;

-- One row per step ATTEMPT, not per step: retries stay visible instead of
-- collapsing into a counter.
CREATE TABLE saga_step (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_id      UUID         NOT NULL REFERENCES saga_instance (id) ON DELETE CASCADE,
    step_number  INTEGER      NOT NULL,
    step_name    VARCHAR(64)  NOT NULL,
    attempt      INTEGER      NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ,
    error        TEXT,
    CONSTRAINT ck_saga_step_status CHECK (status IN ('EXECUTING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_saga_step_saga ON saga_step (saga_id, step_number, attempt);

CREATE TABLE compensation_record (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_id     UUID         NOT NULL REFERENCES saga_instance (id) ON DELETE CASCADE,
    step_number INTEGER      NOT NULL,
    step_name   VARCHAR(64)  NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    executed_at TIMESTAMPTZ  NOT NULL,
    error       TEXT,
    CONSTRAINT ck_compensation_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);
CREATE INDEX ix_compensation_saga ON compensation_record (saga_id, step_number DESC);

-- Consumer-level idempotency (ADR-0006 layer 2). Written in the SAME
-- transaction as the state change it guards.
CREATE TABLE processed_event (
    consumer_group VARCHAR(128) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
CREATE INDEX ix_processed_event_at ON processed_event (processed_at);

-- Outcome of matching, with the explanation that justifies it.
CREATE TABLE reconciliation_case (
    id               UUID         PRIMARY KEY,
    version          BIGINT       NOT NULL DEFAULT 0,
    transaction_id   UUID         NOT NULL,
    correlation_id   UUID         NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    classification   VARCHAR(32),
    rule_id          VARCHAR(64),
    rule_set_version VARCHAR(64),
    -- The structured MatchExplanation. JSONB here is correct: it is queried into
    -- (e.g. "cases matched by rule X") rather than replayed verbatim, which is
    -- why it differs from idempotency_record.response_body — see txn V3.
    explanation      JSONB,
    opened_at        TIMESTAMPTZ  NOT NULL,
    closed_at        TIMESTAMPTZ,
    CONSTRAINT ck_case_state CHECK (state IN (
        'OPEN', 'MATCHING', 'AUTO_MATCHED', 'PARTIALLY_MATCHED', 'BREAK_RAISED',
        'UNDER_REVIEW', 'RESOLVED_MATCHED', 'RESOLVED_WRITTEN_OFF',
        'RESOLVED_REJECTED', 'CLOSED'))
);
CREATE INDEX ix_case_transaction ON reconciliation_case (transaction_id);
CREATE INDEX ix_case_state       ON reconciliation_case (state, opened_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON
    saga_instance, saga_step, compensation_record, processed_event, reconciliation_case TO lg_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lg_app;
