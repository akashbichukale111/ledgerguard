-- ---------------------------------------------------------------------------
-- V2: the Transaction aggregate and its ledger entries.
--
-- Monetary columns are NUMERIC(19,4) + CHAR(3), never one column and never a
-- floating-point type (ADR-0009). The scale of 4 exceeds every ISO 4217 minor
-- unit count (max 3) so no currency loses precision at rest; Money enforces the
-- per-currency scale in the domain.
-- ---------------------------------------------------------------------------

CREATE TABLE transaction (
    id              UUID          PRIMARY KEY,

    -- Optimistic concurrency (ADR-0006, layer 3). A lost update here is a lost
    -- financial instruction, so the check is structural rather than advisory.
    version         BIGINT        NOT NULL DEFAULT 0,

    reference       VARCHAR(140)  NOT NULL,
    counterparty_id VARCHAR(128)  NOT NULL,

    amount          NUMERIC(19,4) NOT NULL,
    currency        CHAR(3)       NOT NULL,

    direction       VARCHAR(8)    NOT NULL,
    status          VARCHAR(32)   NOT NULL,

    value_date      DATE          NOT NULL,
    posting_date    DATE,
    settlement_system VARCHAR(64),

    occurred_at     TIMESTAMPTZ   NOT NULL,
    recorded_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    correlation_id  UUID          NOT NULL,

    CONSTRAINT ck_transaction_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_transaction_status CHECK (status IN (
        'RECEIVED', 'VALIDATING', 'VALIDATED', 'PROCESSING', 'RECONCILING',
        'MATCHED', 'UNMATCHED', 'EXCEPTION', 'COMPENSATING', 'COMPENSATED',
        'FAILED', 'COMPLETED')),
    -- Rejects a negative amount at the database boundary as well as in Money:
    -- direction carries the sign, the amount never does.
    CONSTRAINT ck_transaction_amount_non_negative CHECK (amount >= 0)
);

CREATE INDEX ix_transaction_reference      ON transaction (reference);
CREATE INDEX ix_transaction_counterparty   ON transaction (counterparty_id, value_date DESC);
CREATE INDEX ix_transaction_status         ON transaction (status, occurred_at DESC);
CREATE INDEX ix_transaction_correlation    ON transaction (correlation_id);
-- Supports keyset pagination: sort key plus a unique tiebreaker (ADR-0008).
CREATE INDEX ix_transaction_keyset         ON transaction (occurred_at DESC, id DESC);

COMMENT ON COLUMN transaction.amount IS
    'NUMERIC(19,4). Never a float. Scale 4 exceeds every ISO 4217 minor-unit '
    'count so no currency loses precision at rest. See ADR-0009.';

-- --- Double-entry postings ---------------------------------------------------
CREATE TABLE ledger_entry (
    id             UUID          PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transaction (id) ON DELETE RESTRICT,

    account        VARCHAR(64)   NOT NULL,
    entry_type     VARCHAR(8)    NOT NULL,

    amount         NUMERIC(19,4) NOT NULL,
    currency       CHAR(3)       NOT NULL,

    value_date     DATE          NOT NULL,
    posting_date   DATE          NOT NULL,

    matched        BOOLEAN       NOT NULL DEFAULT FALSE,
    recorded_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_amount_positive CHECK (amount > 0)
);

CREATE INDEX ix_ledger_entry_transaction ON ledger_entry (transaction_id);
CREATE INDEX ix_ledger_entry_account     ON ledger_entry (account, value_date DESC);
-- Blocking key for the matching engine: currency + amount + date window.
CREATE INDEX ix_ledger_entry_blocking    ON ledger_entry (currency, amount, value_date)
    WHERE matched = FALSE;

COMMENT ON TABLE ledger_entry IS
    'Double-entry postings. Debits and credits must balance per currency; that '
    'invariant is enforced in the domain, not by a database constraint, because '
    'it spans rows and is checked before the aggregate is persisted.';

GRANT SELECT, INSERT, UPDATE, DELETE ON transaction, ledger_entry TO lg_app;
