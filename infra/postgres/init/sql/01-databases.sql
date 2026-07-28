-- ---------------------------------------------------------------------------
-- PostgreSQL bootstrap. Runs ONCE, on first container start, as the superuser.
--
-- This file creates databases and roles only. Application schema is owned by
-- Flyway migrations inside each service, so that schema changes are versioned,
-- reviewed, and replayable from scratch. Putting table DDL here would make it
-- invisible to Flyway and impossible to evolve.
-- ---------------------------------------------------------------------------

-- Least-privilege role that every service connects as. It is deliberately NOT
-- the superuser: the audit migration REVOKEs UPDATE and DELETE from this role,
-- and that revocation is meaningless if services connect as an owner who can
-- grant it back. See ADR-0014.
--
-- The password arrives as a psql variable from 01-bootstrap.sh. Using
-- :'app_password' rather than string interpolation means psql does the
-- quoting, so a password containing a quote cannot break out of the literal.
CREATE ROLE lg_app WITH LOGIN PASSWORD :'app_password';

-- One database per bounded context. Separate databases rather than separate
-- schemas so that a service cannot accidentally read another service's tables
-- through a search_path mistake.
CREATE DATABASE ledgerguard_txn   OWNER ledgerguard;
CREATE DATABASE ledgerguard_recon OWNER ledgerguard;
CREATE DATABASE ledgerguard_audit OWNER ledgerguard;

GRANT CONNECT ON DATABASE ledgerguard_txn   TO lg_app;
GRANT CONNECT ON DATABASE ledgerguard_recon TO lg_app;
GRANT CONNECT ON DATABASE ledgerguard_audit TO lg_app;
