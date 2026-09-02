-- Least-privilege database roles for ipie-user-service and ipie-iam-service.
-- ARCHITECTURE_WORKING_PLAN.md Stage 5, item 1. Run as a superuser, once per environment.
--
--   psql -h <host> -U postgres -d ipie_user_service \
--        -v user_app_password="'...'" -v user_owner_password="'...'" \
--        -v iam_app_password="'...'"  -v iam_owner_password="'...'" \
--        -f 01-create-roles.sql
--
-- WHY THIS EXISTS
--
-- D6 has the two services sharing the `ipie_user_service` database. The §4.1.1 boundary - iam owns
-- credentials, user-service must never read one - is therefore NOT enforced by the database. It
-- holds only as long as nobody writes the join. `V14` ends with a REVOKE/GRANT block that makes it
-- real, but that block is a no-op while both services connect as `postgres`: a superuser ignores
-- grants entirely. These roles are what give it effect.
--
-- FOUR ROLES, NOT TWO
--
-- Each service gets an owner and an app role, because Flyway needs DDL and the running application
-- does not:
--
--   <service>_owner   owns the tables, runs Flyway. CREATE on the schema.
--   <service>_app     the runtime connection. SELECT/INSERT/UPDATE/DELETE on its own tables only.
--                     No CREATE, no DROP, no rights on the other service's tables.
--
-- Spring wires this with spring.flyway.user/password (owner) separate from
-- spring.datasource.username/password (app) - see each service's application.yml.
--
-- If the app role could also do DDL, "least privilege" would mean nothing: anything able to create
-- a table can drop the grants that constrain it.
--
-- NOTE ON EXTENSIONS: `CREATE EXTENSION pgcrypto` (iam's V1) requires a superuser. It is created
-- here so that a fresh database can be migrated by a non-superuser owner role afterwards.

-- ---------------------------------------------------------------------------------------------
-- EXTENSIONS
--
-- pg_trgm backs the trigram indexes user-service V12 creates for its user search. It is here rather
-- than in that migration because CREATE EXTENSION needs CREATE on the database, which the owner
-- roles below deliberately do not have - they own tables, not the database. pg_trgm is a trusted
-- extension from PostgreSQL 13, so this works on a managed instance without full superuser too.
--
-- Run before the migrations. A service starting against a database without it fails on V12 with
-- "operator class gin_trgm_ops does not exist", which names the cause plainly.
-- ---------------------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;



\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- --- roles -------------------------------------------------------------------------------------
-- Idempotent: re-running this script must not fail, and must not silently reset a password that
-- the deployment's secret store considers current.

-- \gexec rather than a DO block: psql does not interpolate :'variables' inside dollar-quoted
-- strings, so a password passed with -v would be sent to the server literally as ":'password'".
-- Each SELECT produces the CREATE ROLE statement only when the role is absent, and \gexec runs it.
--
-- Note the absent semicolons: `SELECT ...;` followed by \gexec would run the query twice - once
-- displayed, once executed - and the displayed copy prints the password to stdout, where it lands
-- in whatever log the deployment captures. Without the semicolon only \gexec runs, silently.

SELECT format('CREATE ROLE ipie_user_service_owner LOGIN PASSWORD %L', :'user_owner_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipie_user_service_owner')
\gexec

SELECT format('CREATE ROLE ipie_user_service_app LOGIN PASSWORD %L', :'user_app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipie_user_service_app')
\gexec

SELECT format('CREATE ROLE ipie_iam_service_owner LOGIN PASSWORD %L', :'iam_owner_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipie_iam_service_owner')
\gexec

SELECT format('CREATE ROLE ipie_iam_service_app LOGIN PASSWORD %L', :'iam_app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipie_iam_service_app')
\gexec

-- --- database and schema access ------------------------------------------------------------------

GRANT CONNECT ON DATABASE ipie_user_service
    TO ipie_user_service_owner, ipie_user_service_app, ipie_iam_service_owner, ipie_iam_service_app;

-- Postgres grants CONNECT on every database to PUBLIC by default, so the GRANT above is not what
-- lets these roles in - it is merely explicit. Without the REVOKE below, ANY role in the cluster can
-- open a connection to this database: table grants still refuse the data, but an unrelated service's
-- account can reach the server, enumerate what exists, and sit in the connection count. Revoking
-- from PUBLIC is what makes the grant mean something.
--
-- Verified 2026-08-14: before this, ipie_communication_service_app could connect to
-- ipie_user_service and was stopped only at the table.
REVOKE CONNECT ON DATABASE ipie_user_service FROM PUBLIC;

-- Owners create tables; app roles only reach into the schema to use what is already there.
GRANT USAGE, CREATE ON SCHEMA public TO ipie_user_service_owner, ipie_iam_service_owner;
GRANT USAGE ON SCHEMA public TO ipie_user_service_app, ipie_iam_service_app;

-- Postgres 15+ already revokes this; stated explicitly because the whole model rests on it and a
-- database restored from an older dump will not have it.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- --- one-time ownership transfer -----------------------------------------------------------------
--
-- Only needed for a database that already exists - one created fresh under these roles has correct
-- ownership from its first migration. The lists are explicit rather than pattern-matched: which
-- service owns which table is a boundary decision, and it should be reviewable at a glance rather
-- than inferred from a prefix. Cross-check against ARCHITECTURE_WORKING_PLAN.md §4.0 before editing.
--
-- Each service's Flyway history table moves with it, so each owner can write its own timeline.

DO $$
DECLARE
    user_tables text[] := ARRAY[
        'users', 'organisations', 'pillar_links', 'pillar_link_requests',
        'identity_proof_types', 'legal_representative_types', 'professional_identification_types',
        'professional_roles', 'audit_trail', 'outbox_events', 'processed_events',
        'flyway_schema_history'
    ];
    iam_tables text[] := ARRAY[
        'user_credentials', 'credential_setup_tokens', 'roles', 'permissions', 'role_permissions',
        'user_roles', 'pillar_resolution', 'iam_audit_trail', 'iam_outbox_events',
        'iam_processed_events', 'flyway_schema_history_iam'
    ];
    target text;
BEGIN
    FOREACH target IN ARRAY user_tables LOOP
        IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = target) THEN
            EXECUTE format('ALTER TABLE public.%I OWNER TO ipie_user_service_owner', target);
        END IF;
    END LOOP;

    FOREACH target IN ARRAY iam_tables LOOP
        IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = target) THEN
            EXECUTE format('ALTER TABLE public.%I OWNER TO ipie_iam_service_owner', target);
        END IF;
    END LOOP;
END
$$;

-- Sequences follow their table's owner. Identity/serial columns own their sequence, so this catches
-- anything the ALTER TABLE above did not.
-- (Entity ids are application-generated UUIDs, so there may well be none - this is here so that a
-- future serial column does not quietly stay owned by postgres.)
DO $$
DECLARE
    sequence_name text;
    new_owner text;
BEGIN
    FOR sequence_name, new_owner IN
        SELECT s.relname, pg_get_userbyid(t.relowner)
        FROM pg_class s
        JOIN pg_namespace n ON n.oid = s.relnamespace AND n.nspname = 'public'
        JOIN pg_depend d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'a'
        JOIN pg_class t ON t.oid = d.refobjid
        WHERE s.relkind = 'S'
    LOOP
        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO %I', sequence_name, new_owner);
    END LOOP;
END
$$;

-- --- what is deliberately NOT here ---------------------------------------------------------------
--
-- No grants between the two services. Each service's own migration grants DML on the tables it owns
-- to its own app role (ipie-user-service V30, ipie-iam-service V17), and V14 additionally REVOKEs
-- the credential tables from ipie_user_service_app so the intent survives a future blanket grant.
--
-- After running this, verify the boundary actually holds:
--
--   \c ipie_user_service ipie_user_service_app
--   SELECT * FROM user_credentials;     -- must fail: permission denied
--   SELECT count(*) FROM users;         -- must succeed
