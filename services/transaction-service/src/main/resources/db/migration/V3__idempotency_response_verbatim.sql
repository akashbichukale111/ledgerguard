-- ---------------------------------------------------------------------------
-- V3: store the idempotent replay response verbatim.
--
-- V1 declared idempotency_record.response_body as JSONB. PostgreSQL's jsonb type
-- NORMALISES on write — it reorders keys and re-spaces the document — so a
-- replayed response came back semantically equal but not byte-equal to the one
-- originally sent:
--
--   sent:     {"status":"RECEIVED","transactionId":"..."}
--   replayed: {"status": "RECEIVED", "transactionId": "..."}
--
-- The API contract is that a replay returns THE ORIGINAL RESPONSE. A client that
-- hashes or signature-checks the body would see the two differ. jsonb is the
-- wrong type here: this column holds an opaque previously-sent payload, not a
-- document we ever query into.
--
-- V1 is immutable and is not edited; this fixes forward.
-- ---------------------------------------------------------------------------

ALTER TABLE idempotency_record
    ALTER COLUMN response_body TYPE TEXT USING response_body::text;

COMMENT ON COLUMN idempotency_record.response_body IS
    'The exact bytes of the original response. TEXT, not JSONB: jsonb normalises '
    'whitespace and key order, which would break byte-identical replay.';
