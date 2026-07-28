-- ---------------------------------------------------------------------------
-- V2: Allow null record_hash initially to support two-phase hashing.
--
-- The hash cannot be computed before chainIndex is assigned by the database,
-- so we insert with a null record_hash and seal it in a subsequent UPDATE.
-- The application layer guarantees that record_hash is always sealed before
-- any queries read the table.
-- ---------------------------------------------------------------------------

ALTER TABLE audit_event ALTER COLUMN record_hash DROP NOT NULL;
