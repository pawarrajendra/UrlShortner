#!/usr/bin/env bash
set -euo pipefail
: "${DATABASE_PASSWORD:?Runtime database password is required}"
: "${DATABASE_MIGRATION_PASSWORD:?Migration database password is required}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_password DATABASE_PASSWORD
\getenv migrator_password DATABASE_MIGRATION_PASSWORD
\getenv database_name POSTGRES_DB
CREATE ROLE urlshortener_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
CREATE ROLE urlshortener_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migrator_password';
REVOKE ALL ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO urlshortener_app, urlshortener_migrator;
ALTER SCHEMA public OWNER TO urlshortener_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO urlshortener_app;
ALTER DEFAULT PRIVILEGES FOR ROLE urlshortener_migrator IN SCHEMA public GRANT SELECT ON TABLES TO urlshortener_app;
ALTER ROLE urlshortener_app SET statement_timeout = '1s';
ALTER ROLE urlshortener_app SET lock_timeout = '500ms';
SQL
