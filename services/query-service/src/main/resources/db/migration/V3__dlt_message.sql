-- Dead-letter messages, captured so operators can see and replay them.
--
-- Until now nothing persisted DLT traffic: the messages existed only on the Kafka topic, where
-- they aged out with the retention policy and were invisible to the console. The DLT explorer had
-- no data source at all.
CREATE TABLE dlt_message (
    message_id          UUID PRIMARY KEY,
    source_topic        TEXT        NOT NULL,
    partition_number    INT         NOT NULL,
    record_offset       BIGINT      NOT NULL,
    reason              TEXT        NOT NULL,
    stack_trace_digest  TEXT,
    attempt_count       INT         NOT NULL,
    original_envelope   TEXT        NOT NULL,
    first_failed_at     TIMESTAMPTZ,
    occurred_at         TIMESTAMPTZ NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    replayed_at         TIMESTAMPTZ
);

-- The console lists newest-first and pages; without this the scan is the whole table.
CREATE INDEX idx_dlt_message_recorded_at ON dlt_message (recorded_at DESC, message_id DESC);

-- Redelivery of the same dead letter must not create a second row. A DLT message is identified
-- by where it came from, not by when we happened to read it.
CREATE UNIQUE INDEX idx_dlt_message_origin ON dlt_message (source_topic, partition_number, record_offset);
