#!/bin/bash
set -e

# Keycloak needs its own database so its internal tables (users, realms,
# sessions, etc.) don't mix with the CodeRank application schema.
# Using a separate DB inside the SAME Postgres instance avoids spinning
# up a second Postgres container while still keeping data isolated.
# This script runs only on first container initialization (via the
# docker-entrypoint-initdb.d mechanism) -- it is a no-op on restarts.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE keycloak;
EOSQL
