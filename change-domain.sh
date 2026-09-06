#!/usr/bin/env bash
# Changes the platform's email domain across every service, now and for services added later.
#
#   ./change-domain.sh --from ipie.gov.in --to ipie.example.in [--dry-run] [--workspace <dir>]
#
# WHY THIS IS A SCRIPT AND NOT A FIND-AND-REPLACE
#
# Most occurrences of the domain are email addresses seeded inside Flyway migrations, and an
# applied migration cannot be edited: Flyway records its checksum, and changing so much as a
# comment makes the service refuse to start with "Migration checksum mismatch". A blanket sed over
# *.sql produces a platform that builds, passes every test, and then will not boot against any
# database that already ran those migrations.
#
# So this script splits the work in two:
#
#   files that are NOT migrations   edited in place
#   migrations                      never touched; a NEW migration is generated per service
#
# The generated migration does not name a single table. It walks information_schema and rewrites
# any text column whose value carries the old domain, which is what makes it work for services
# that do not exist yet - a service added next year gets the same migration generated for its own
# schema, with no edit to this script.
#
# WHAT IT DELIBERATELY DOES NOT TOUCH
#
#   The Java package `in.gov.ipie`. It is an identifier, not an address: nothing resolves it, and
#   no part of the platform requires it to match a live domain. Renaming it means 460 source files,
#   every ArchitectureTest's BASE_PACKAGE, new-service.sh, the AutoConfiguration.imports files, the
#   Maven group - and 38 spotbugs-exclude.xml entries matched by EXACT type name, which fail
#   silently when they no longer match. That is a separate, deliberate decision; see
#   MASTER_CODE_STANDARDS.md section 5.
#
#   A RUNNING Keycloak realm. deploy/keycloak/realm-export.json is edited like any other file, but
#   a realm already imported holds its own copy of every user. Do NOT fix that with a partial
#   import: OVERWRITE silently discards user role mappings. Update the users through the admin
#   users endpoint instead. The script prints the affected accounts and stops there.
set -uo pipefail

FROM=""; TO=""; DRY_RUN=false
WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="${2:?--from needs a domain}"; shift 2 ;;
    --to) TO="${2:?--to needs a domain}"; shift 2 ;;
    --workspace) WORKSPACE="${2:?--workspace needs a path}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$FROM" ] && [ -n "$TO" ] || { echo "both --from and --to are required" >&2; exit 2; }
[ "$FROM" = "$TO" ] && { echo "--from and --to are the same" >&2; exit 2; }
for d in "$FROM" "$TO"; do
  printf '%s' "$d" | grep -qE '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$' \
    || { echo "not a domain: $d" >&2; exit 2; }
done

BOLD=$'\e[1m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; OFF=$'\e[0m'
step() { printf "\n${BOLD}%s${OFF}\n" "$1"; }
$DRY_RUN && echo "${YELLOW}(dry run - nothing is written)${OFF}"

# --- discover the estate, rather than hardcoding it ----------------------------------------------
# A service repository is one whose gradle.properties pins the platform. The platform repository
# itself is included by name, and ipie-web sits one level up from the backend workspace.
REPOS=()
for d in "$WORKSPACE"/*/; do
  [ -f "$d/gradle.properties" ] || continue
  if grep -q '^ipiePlatformVersion=' "$d/gradle.properties" 2>/dev/null \
     || [ "$(basename "$d")" = "ipie-platform-mca" ]; then
    REPOS+=("${d%/}")
  fi
done
[ -d "$WORKSPACE/../ipie-web" ] && REPOS+=("$(cd "$WORKSPACE/../ipie-web" && pwd)")

step "1. Estate"
for r in "${REPOS[@]}"; do printf "   %s\n" "$(basename "$r")"; done

# --- what carries the domain ---------------------------------------------------------------------
step "2. Occurrences of ${FROM}"
declare -A EDITABLE MIGRATIONS
total_edit=0; total_mig=0
for r in "${REPOS[@]}"; do
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$(grep -c "$FROM" "$f")
    case "$f" in
      */db/migration/*) MIGRATIONS["$f"]=$n; total_mig=$((total_mig + n)) ;;
      *)                EDITABLE["$f"]=$n;   total_edit=$((total_edit + n)) ;;
    esac
  done < <(grep -rl --binary-files=without-match "$FROM" "$r" \
             --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
             --exclude-dir=node_modules --exclude-dir=dist \
             --exclude="$(basename "$0")" --exclude='*.bak' --exclude='*BACKUP*' 2>/dev/null)
done
printf "   %-46s %3s occurrences in %s files\n" "editable in place" "$total_edit" "${#EDITABLE[@]}"
printf "   %-46s %3s occurrences in %s files  ${YELLOW}(never edited)${OFF}\n" "inside applied migrations" "$total_mig" "${#MIGRATIONS[@]}"
for f in "${!MIGRATIONS[@]}"; do printf "      %s\n" "${f#$WORKSPACE/}"; done

# --- edit everything that is not a migration -------------------------------------------------------
step "3. Rewriting files"
if [ "${#EDITABLE[@]}" -eq 0 ]; then
  echo "   nothing to rewrite"
else
  for f in "${!EDITABLE[@]}"; do
    printf "   %-70s %s\n" "${f#$WORKSPACE/}" "${EDITABLE[$f]}"
    $DRY_RUN || sed -i "s/${FROM//./\\.}/${TO}/g" "$f"
  done
fi

# --- one generated migration per service that has a schema -----------------------------------------
step "4. Generating migrations"
SAFE_TO=$(printf '%s' "$TO" | tr '.-' '__')
for r in "${REPOS[@]}"; do
  mig="$r/src/main/resources/db/migration"
  [ -d "$mig" ] || continue
  # ipie-service-template is cloned, not deployed. A domain-change migration in its baseline would
  # travel into every future service as a migration that can never have anything to update.
  [ "$(basename "$r")" = "ipie-service-template" ] && { printf "   %-52s %s\n" "$(basename "$r")" "skipped (template, not deployed)"; continue; }
  next=$(( $(ls "$mig" | sed -n 's/^V\([0-9]\+\)__.*/\1/p' | sort -n | tail -1) + 1 ))
  out="$mig/V${next}__change_email_domain_to_${SAFE_TO}.sql"
  printf "   %-52s V%s\n" "$(basename "$r")" "$next"
  $DRY_RUN && continue
  cat > "$out" <<SQL
-- Moves every stored email address from @${FROM} to @${TO}.
--
-- WHY THIS IS A MIGRATION AND NOT AN EDIT TO THE ONES THAT SEEDED THOSE ADDRESSES.
-- Flyway records a checksum for every migration it applies. Editing an applied file - even a
-- comment inside it - makes this service refuse to start with "Migration checksum mismatch"
-- against any database that already ran it. The seeded addresses stay exactly as they were
-- written; this migration moves the rows.
--
-- WHY IT NAMES NO TABLE. It walks information_schema and rewrites any character column holding
-- the old domain, so it is correct for whatever this service's schema happens to be - including a
-- service that did not exist when the script generating this was written. Views, foreign tables
-- and Flyway's own history are excluded; the history table is what records this migration.
--
-- IDEMPOTENT: a second run matches nothing, because the old domain is gone by then.

DO \$\$
DECLARE
    col RECORD;
    updated BIGINT;
BEGIN
    FOR col IN
        SELECT c.table_name, c.column_name
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema = c.table_schema AND t.table_name = c.table_name
         WHERE c.table_schema = 'public'
           AND t.table_type   = 'BASE TABLE'
           AND c.data_type IN ('character varying', 'text', 'character')
           AND c.is_generated = 'NEVER'
           AND c.table_name NOT LIKE 'flyway_schema_history%'
    LOOP
        EXECUTE format(
            'UPDATE public.%I SET %I = replace(%I, %L, %L) WHERE %I LIKE %L',
            col.table_name, col.column_name, col.column_name,
            '@${FROM}', '@${TO}', col.column_name, '%@${FROM}');
        GET DIAGNOSTICS updated = ROW_COUNT;
        IF updated > 0 THEN
            RAISE NOTICE 'domain change: %.% - % row(s)', col.table_name, col.column_name, updated;
        END IF;
    END LOOP;
END \$\$;
SQL
done

# --- what the script will not do for you ------------------------------------------------------------
step "5. Left for you, deliberately"
cat <<TXT
   ${YELLOW}A running Keycloak realm${OFF}
     deploy/keycloak/realm-export.json has been rewritten, but a realm already imported holds its
     own copy of every user. Do NOT re-import with OVERWRITE - it silently discards user role
     mappings. Update each account through the admin users endpoint instead:

       GET  /admin/realms/ipie/users?email=<address>
       PUT  /admin/realms/ipie/users/{id}     with the new email and username

   ${YELLOW}The Java package${OFF}
     in.gov.ipie is untouched by design - see this script's header and
     MASTER_CODE_STANDARDS.md section 5.

   ${YELLOW}Verify before committing${OFF}
     ./gradlew check in each repository, then start-stack.sh smoke - the smoke test logs in as a
     seeded account and is the one check that proves the migration and the realm agree.
TXT
$DRY_RUN && echo "\n${YELLOW}Dry run complete - nothing was written.${OFF}" || echo "\n${GREEN}Done.${OFF}"
