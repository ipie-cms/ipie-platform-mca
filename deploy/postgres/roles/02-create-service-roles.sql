-- Least-privilege database roles for any service that owns its database outright.
-- ARCHITECTURE_WORKING_PLAN.md Stage 5, item 1. Run as a superuser, once per environment per service.
--
--   psql -h <host> -U postgres -d ipie_communication_service \
--        -v service=ipie_communication_service \
--        -v app_password="'...'" -v owner_password="'...'" \
--        -f 02-create-service-roles.sql
--
-- WHY THIS IS PARAMETERISED AND 01-create-roles.sql IS NOT
--
-- 01 handles the one genuinely special case: ipie-user-service and ipie-iam-service SHARE the
-- `ipie_user_service` database (D6), so which table belongs to which service is a boundary decision
-- that has to be written out and reviewed. Nothing can infer it.
--
-- Every other service owns its database alone. There is no boundary to draw, so "every table in the
-- public schema belongs to this service" is not a shortcut - it is the fact. That makes the script
-- generic, and a new service gets least privilege by running it rather than by someone remembering
-- to extend a hardcoded list. A service that later shares a database has left this case and needs
-- 01's treatment instead.
--
-- FOUR ROLES ACROSS TWO SCRIPTS, ONE RULE: the owner runs Flyway and owns the tables; the app role
-- is the runtime connection and gets DML only. A runtime connection able to create a table is able
-- to drop the grants that constrain it, which is the whole reason the split exists.
--
-- \gexec throughout rather than DO blocks: psql does not interpolate :'variables' inside
-- dollar-quoted strings, so a DO block cannot see the parameters this script is given. Note the
-- absent semicolons before each \gexec - `SELECT ...;` would run the query twice, once displayed,
-- and the displayed copy prints the password into whatever log the deployment captures.

\set ON_ERROR_STOP on

-- pgcrypto needs a superuser, so it is created here rather than in a migration - otherwise a fresh
-- database cannot be migrated by the non-superuser owner role afterwards.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service' || '_owner', :'owner_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'service' || '_owner')
\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service' || '_app', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'service' || '_app')
\gexec

-- --- database and schema access ------------------------------------------------------------------
-- current_database() rather than a second parameter: the script must be run against the database it
-- is configuring anyway, and deriving it removes a way to get the two out of step.

SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I',
              current_database(), :'service' || '_owner', :'service' || '_app')
\gexec

-- Postgres grants CONNECT on every database to PUBLIC by default, so the GRANT above is not what
-- lets these roles in - it is merely explicit. Without the REVOKE below, ANY role in the cluster can
-- open a connection to this database: table grants still refuse the data, but an unrelated service's
-- account can reach the server, enumerate what exists, and sit in the connection count. Revoking
-- from PUBLIC is what makes the grant mean something.
--
-- Verified 2026-08-14: before this, ipie_communication_service_app could connect to
-- ipie_user_service and was stopped only at the table.

SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', current_database())
\gexec

SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'service' || '_owner')
\gexec

SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'service' || '_app')
\gexec

-- Postgres 15+ already revokes this; stated explicitly because the model rests on it and a database
-- restored from an older dump will not have it.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- --- one-time ownership transfer -----------------------------------------------------------------
--
-- Only needed for a database that already exists; one created fresh under these roles has correct
-- ownership from its first migration. Every table, because this service owns the database - see the
-- header. That includes flyway_schema_history, which moves to the owner precisely so the app role
-- cannot touch the migration timeline.

SELECT format('ALTER TABLE public.%I OWNER TO %I', tablename, :'service' || '_owner')
FROM pg_tables
WHERE schemaname = 'public'
\gexec

-- Sequences follow their owning table. (Entity ids are application-generated UUIDs, so there may be
-- none - this is here so a future serial column does not quietly stay owned by postgres.)
SELECT format('ALTER SEQUENCE public.%I OWNER TO %I', s.relname, pg_get_userbyid(t.relowner))
FROM pg_class s
JOIN pg_namespace n ON n.oid = s.relnamespace AND n.nspname = 'public'
JOIN pg_depend d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'a'
JOIN pg_class t ON t.oid = d.refobjid
WHERE s.relkind = 'S'
\gexec

-- DML for the app role is granted by the service's OWN migration, not here, so it travels with the
-- schema instead of depending on this script being re-run after every new table. See
-- ipie-communication-service's V12 for the pattern.
--
-- Verify afterwards that the split is real:
--
--   \c <database> <service>_app
--   CREATE TABLE nope (x int);   -- must fail: permission denied for schema public
--   SELECT count(*) FROM <a table it owns>;  -- must succeed
