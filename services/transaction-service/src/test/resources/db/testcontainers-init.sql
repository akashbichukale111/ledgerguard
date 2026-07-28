-- Creates the least-privilege role the migrations GRANT to.
--
-- In the real stack this role is created by infra/postgres/init. Recreating it here means the
-- migrations run byte-identically in test and production, rather than maintaining a test-only
-- variant that could drift from the one that actually ships.
CREATE ROLE lg_app WITH LOGIN PASSWORD 'test_only_not_a_secret';
