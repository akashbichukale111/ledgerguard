-- Creates the least-privilege role the migrations GRANT to, mirroring
-- infra/postgres/init so the migrations run byte-identically in test and production.
CREATE ROLE lg_app WITH LOGIN PASSWORD 'test_only_not_a_secret';
