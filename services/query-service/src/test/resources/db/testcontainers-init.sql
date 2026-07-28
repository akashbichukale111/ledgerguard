-- Mirrors infra/postgres/init so the audit migration — including its REVOKE — runs
-- byte-identically in test and production.
CREATE ROLE lg_app WITH LOGIN PASSWORD 'test_only_not_a_secret';
