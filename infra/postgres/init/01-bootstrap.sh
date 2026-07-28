#!/bin/sh
# Runs once, on first container start, as the superuser.
#
# The SQL lives in sql/ rather than beside this script because the Postgres
# entrypoint scans /docker-entrypoint-initdb.d non-recursively: a .sql file at
# the top level would be executed directly, without the app_password variable
# this script supplies.
#
# The password is passed as a psql variable, never written to a file. The mount
# is read-only, and a bootstrap that needs to mutate its own source is a
# bootstrap that will break the moment someone hardens the mount.
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER}" \
     --dbname postgres \
     --set=app_password="${POSTGRES_APP_PASSWORD}" \
     --file /docker-entrypoint-initdb.d/sql/01-databases.sql

echo "ledgerguard: postgres bootstrap complete"
