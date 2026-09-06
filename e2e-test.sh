#!/usr/bin/env bash
# End-to-end verification of the rebuilt stack against freshly created databases.
#
# Covers the whole chain rather than each service alone: Keycloak issues a token only if the SPI can
# reach iam and iam's Argon2id hash verifies, and a registration only completes if user-service,
# RabbitMQ, iam and comms all play their part.
#
# Keycloak runs on the Windows host bound so that only containers reach it, so anything involving a
# token is driven from a throwaway container; the services themselves are reached on their published
# ports.

USER_SVC=http://127.0.0.1:8092
IAM_SVC=http://127.0.0.1:8093
COMMS_SVC=http://127.0.0.1:8094
MAILHOG=http://127.0.0.1:8025
# The multi-qualification demo account (user-service V6 / iam V19) - the assignment target for the
# ceiling probe below, chosen because it is seeded and holds no admin role of its own.
DEMO_USER_ID=10000000-0000-0000-0000-000000000011
DEMO_KC_ID=20000000-0000-0000-0000-000000000012

pass=0; fail=0
ok()   { printf '  [PASS] %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  [FAIL] %s\n' "$1"; fail=$((fail+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected $3, got $2)"; fi; }

kc() { # run curl inside a container so keycloak: resolves
  docker run --rm --add-host=keycloak:host-gateway curlimages/curl:latest -s "$@" 2>/dev/null
}
psql_iam() {
  docker run --rm --add-host=host.docker.internal:host-gateway -e PGPASSWORD=local-dev-iam-owner \
    postgres:18-alpine psql -h host.docker.internal -U ipie_iam_service_owner -d ipie_user_service -tAc "$1" 2>/dev/null
}
psql_user() {
  docker run --rm --add-host=host.docker.internal:host-gateway -e PGPASSWORD=local-dev-user-owner \
    postgres:18-alpine psql -h host.docker.internal -U ipie_user_service_owner -d ipie_user_service -tAc "$1" 2>/dev/null
}

echo "=== 1. Services are up ==="
for pair in "user:$USER_SVC" "iam:$IAM_SVC" "comms:$COMMS_SVC"; do
  n=${pair%%:*}; u=${pair#*:}
  check "$n health" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 $u/actuator/health)" "200"
done

echo "=== 2. Baseline migrations produced the expected schema and seed data ==="
# `not is_org` since V13: an entity principal is a users row too, and it inherits its
# organisation's created_by - so a bare count of seeded rows now counts entities as people.
check "demo users seeded"        "$(psql_user "select count(*) from users where created_by='flyway-seed' and not is_org")" "11"
# 11 since V6 added multirole.demo@ipie.gov.in - the only seeded account holding more than one
# professional qualification, and therefore the only one that exercises what V5 changed.
check "the multi-role demo account" \
  "$(psql_user "select count(*) from user_professional_roles upr join users u on u.id=upr.user_id where u.email='multirole.demo@ipie.gov.in'")" "2"
# Residue means rows older than the schema they sit in. Anything predating the baseline migration
# survived a rebuild it should not have; an age threshold would instead flag this test's own users
# once they were an hour old.
# Seeded fixtures are excluded: they were captured from the reference database with their original
# timestamps, so they legitimately predate the baseline that inserted them. Any OTHER row older than
# the schema survived a rebuild it should not have.
check "no non-seed rows predate the baseline" "$(psql_user "select count(*) from users where created_by <> 'flyway-seed' and created_at < (select min(installed_on) from flyway_schema_history)")" "0"
check "consent notice seeded"    "$(psql_user "select count(*) from consent_notices")" "1"
# 13 rows since user-service V7 added LEGAL_REPRESENTATIVE - the role RegistrationPolicy has always
# resolved by code and never found, which made every registration carrying an Advocate/CA/CS type
# fail validation. Only 3 are offered: V7 deactivated the ten codes that were stakeholder categories
# or case capacities rather than qualifications, keeping the rows so existing declarations survive.
check "professional roles seeded" "$(psql_user "select count(*) from professional_roles")" "13"
check "only qualifications are offered" "$(psql_user "select count(*) from professional_roles where is_active")" "3"
check "the legal-representative role exists" \
  "$(psql_user "select count(*) from professional_roles where code='LEGAL_REPRESENTATIVE' and is_active")" "1"
check "consent tables exist"     "$(psql_user "select count(*) from information_schema.tables where table_name in ('user_consents','consent_notices')")" "2"
# Checked against both tables since V13 moved the identity proof to `person`: naming only `users`
# would keep passing for the reason the column is not there at all.
check "identity number column is gone" "$(psql_user "select count(*) from information_schema.columns where table_name in ('users','person') and column_name='identity_proof_number'")" "0"

echo "=== 3. The credential boundary holds in the database (D6 / EX-001) ==="
denied=$(docker run --rm --add-host=host.docker.internal:host-gateway -e PGPASSWORD=local-dev-user-app postgres:18-alpine \
  psql -h host.docker.internal -U ipie_user_service_app -d ipie_user_service -tAc "select count(*) from user_credentials" 2>&1 | grep -c "permission denied")
check "user-service role cannot read user_credentials" "$denied" "1"

echo "=== 4. Login: Keycloak -> SPI -> HMAC -> iam -> Argon2id ==="
tok=$(kc -X POST --data-urlencode "client_id=ipie-service-template" \
  --data-urlencode "client_secret=ipie-service-template-secret" \
  --data-urlencode "username=superadmin@ipie.gov.in" --data-urlencode "password=ipie-superadmin-demo" \
  --data-urlencode "grant_type=password" http://keycloak:8080/realms/ipie/protocol/openid-connect/token)
if echo "$tok" | grep -q access_token; then
  ok "superadmin login issued a token"
  perms=$(python3 -c "
import json,base64,sys
d=json.loads(sys.argv[1]); p=d['access_token'].split('.')[1]
c=json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4)))
print(','.join(sorted(c.get('permissions',[]))[:5]))" "$tok" 2>/dev/null)
  echo "         permissions: ${perms:-<none>}"
else
  bad "superadmin login ($(echo "$tok" | head -c 120))"
fi

wrong=$(kc -X POST --data-urlencode "client_id=ipie-service-template" \
  --data-urlencode "client_secret=ipie-service-template-secret" \
  --data-urlencode "username=superadmin@ipie.gov.in" --data-urlencode "password=wrong-password" \
  --data-urlencode "grant_type=password" http://keycloak:8080/realms/ipie/protocol/openid-connect/token)
if echo "$wrong" | grep -q invalid_grant; then
  ok "a wrong password is rejected as invalid_grant, not a 503"
else
  bad "wrong password gave: $(echo "$wrong" | head -c 120)"
fi

echo "=== 5. Registration chain ==="
STAMP=$(date +%s)
EMAIL="e2e${STAMP}@example.gov.in"
reg=$(curl -s -X POST $USER_SVC/api/v1/registrations -H 'Content-Type: application/json' \
  -d "{\"mobileNumber\":\"+91 98${STAMP: -8}\",\"email\":\"$EMAIL\",\"notificationChannels\":[\"EMAIL\",\"SMS\"]}")
RID=$(echo "$reg" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(d.get('registrationId') or d.get('id') or '')" 2>/dev/null)
if [ -n "$RID" ]; then ok "registration created ($RID)"; else bad "registration create: $(echo "$reg" | head -c 200)"; fi

otpcode=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$USER_SVC/api/v1/registrations/$RID/email-otp")
# 202: issuing the code is asynchronous - the mail goes out through the outbox and comms.
check "OTP requested" "$otpcode" "202"

OTP=""
for i in $(seq 1 30); do
  OTP=$(curl -s "$MAILHOG/api/v2/search?kind=to&query=$EMAIL" | python3 -c "
import json,sys,re
d=json.load(sys.stdin)
for m in d.get('items',[]):
    if 'verification code' not in m['Content']['Headers'].get('Subject',[''])[0]: continue
    body=m['Content']['Body'].replace('=\r\n','').replace('=\n','')
    hit=re.search(r'\b(\d{6})\b', body)
    if hit: print(hit.group(1)); break" 2>/dev/null)
  [ -n "$OTP" ] && break; sleep 3
done
if [ -n "$OTP" ]; then ok "OTP email delivered to the registrant"; else bad "no OTP email for $EMAIL"; fi

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$USER_SVC/api/v1/registrations/$RID/email-otp/confirm" \
  -H 'Content-Type: application/json' -d "{\"code\":\"$OTP\"}")
check "OTP confirmed" "$code" "200"

check "OTP is stored hashed, never in clear" \
  "$(psql_user "select count(*) from users where email_otp_code_hash = '$OTP'")" "0"

IPT=$(curl -s $USER_SVC/api/v1/registrations/identity-proof-types | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null)
PR=$(curl -s $USER_SVC/api/v1/registrations/professional-roles | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null)
PIT=$(curl -s $USER_SVC/api/v1/registrations/professional-identification-types | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null)
# A second role, so the run proves multiplicity rather than assuming it (FRS 1.1.1 item 6).
PR2=$(curl -s $USER_SVC/api/v1/registrations/professional-roles | python3 -c "import json,sys; print(json.load(sys.stdin)[1]['id'])" 2>/dev/null)

body=$(cat <<JSON
{"fullName":"E2E Test User","category":"INDIAN","addressLine1":"1 Test Road","country":"India",
 "state":"Delhi","city":"New Delhi","pin":"110001",
 "identityProofTypeId":"$IPT","identityProofNumber":"123456789012",
 "professionalRoles":[
   {"roleId":"$PR","identificationTypeId":"$PIT",
    "identificationValue":"IBBI/IPA-001/IP-P-0001/2026-2027/10001"},
   {"roleId":"$PR2","identificationTypeId":"$PIT",
    "identificationValue":"D/1234/2019"}]}
JSON
)
comp=$(curl -s -X POST "$USER_SVC/api/v1/registrations/$RID/complete" -H 'Content-Type: application/json' -d "$body")
status=$(echo "$comp" | python3 -c "import json,sys; print(json.load(sys.stdin).get('registrationStatus',''))" 2>/dev/null)
if [ "$status" = "PROVISIONING" ]; then ok "complete returned PROVISIONING (no synchronous crossing)"
else bad "complete returned: $(echo "$comp" | head -c 250)"; fi

echo "=== 6. Asynchronous provisioning ==="
for i in $(seq 1 30); do
  kid=$(psql_user "select coalesce(keycloak_user_id::text,'') from users where email='$EMAIL'")
  [ -n "$kid" ] && break; sleep 2
done
if [ -n "$kid" ]; then ok "account provisioned asynchronously (keycloak id assigned)"; else bad "no keycloak id after 60s"; fi

echo "=== 7. Data protection at rest ==="
check "identity number not stored in clear" "$(psql_user "select count(*) from users u join person p on p.user_id = u.id where u.email='$EMAIL' and p.identity_proof_number_last4='9012' and p.identity_proof_number_hash is not null")" "1"
check "every principal has its detail (V13)" "$(psql_user "select count(*) from users u where (not u.is_org and not exists (select 1 from person p where p.user_id=u.id)) or (u.is_org and not exists (select 1 from organisations o where o.user_id=u.id))")" "0"
check "every organisation is a principal" "$(psql_user "select count(*) from organisations where user_id is null")" "0"
check "both professional roles stored" "$(psql_user "select count(*) from user_professional_roles r join users u on u.id=r.user_id where u.email='$EMAIL'")" "2"
check "consent recorded per channel"        "$(psql_user "select count(*) from user_consents c join users u on u.id=c.user_id where u.email='$EMAIL'")" "2"

echo "=== 8. Set-password mail and the credential authority ==="
SETPW=""
for i in $(seq 1 40); do
  SETPW=$(curl -s "$MAILHOG/api/v2/search?kind=to&query=$EMAIL" | python3 -c "
import json,sys,re
d=json.load(sys.stdin)
for m in d.get('items',[]):
    body=m['Content']['Body'].replace('=\r\n','').replace('=3D','=')
    hit=re.search(r'set-password\?token=([A-Za-z0-9_.-]+)', body)
    if hit: print(hit.group(1)); break" 2>/dev/null)
  [ -n "$SETPW" ] && break; sleep 3
done
if [ -n "$SETPW" ]; then ok "set-password link emailed to the registrant"; else bad "no set-password link for $EMAIL"; fi

# The rate-limit probe further down consumes this endpoint's budget, so a second run inside the
# same minute would fail here for a reason that has nothing to do with what is being tested. Wait
# the window out rather than reporting a false failure.
set_password() {
  curl -s -o /dev/null -w '%{http_code}' -X POST $IAM_SVC/api/v1/credentials/password \
    -H 'Content-Type: application/json' -d "{\"token\":\"$SETPW\",\"password\":\"E2eTest!Passw0rd\"}"
}
code=$(set_password)
if [ "$code" = "429" ]; then
  echo "         rate-limited from an earlier run; waiting out the window"
  sleep 62
  code=$(set_password)
fi
check "password set against iam" "$code" "204"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST $IAM_SVC/api/v1/credentials/password \
  -H 'Content-Type: application/json' -d "{\"token\":\"$SETPW\",\"password\":\"E2eTest!Passw0rd\"}")
check "the setup token is single-use" "$code" "422"

newtok=$(kc -X POST --data-urlencode "client_id=ipie-service-template" \
  --data-urlencode "client_secret=ipie-service-template-secret" \
  --data-urlencode "username=$EMAIL" --data-urlencode "password=E2eTest!Passw0rd" \
  --data-urlencode "grant_type=password" http://keycloak:8080/realms/ipie/protocol/openid-connect/token)
if echo "$newtok" | grep -q access_token; then ok "the newly registered user can log in"
else bad "new user login: $(echo "$newtok" | head -c 150)"; fi

echo "=== 9. The delegation ceiling (Stage 10.3) ==="
# A pillar admin holds ROLES_MANAGE and nothing above it. Before the ceiling this returned 204 and
# handed out SUPER_ADMIN; the assertion is here because the hole was invisible to every other test.
pa_tok=$(kc -X POST --data-urlencode "client_id=ipie-service-template" \
  --data-urlencode "client_secret=ipie-service-template-secret" \
  --data-urlencode "username=admin@ipie.gov.in" --data-urlencode "password=ipie-admin-demo" \
  --data-urlencode "grant_type=password" http://keycloak:8080/realms/ipie/protocol/openid-connect/token \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
esc=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$IAM_SVC/api/v1/users/$DEMO_USER_ID/roles" \
  -H "Authorization: Bearer $pa_tok" -H 'Content-Type: application/json' \
  -d "{\"keycloakUserId\":\"$DEMO_KC_ID\",\"roleName\":\"SUPER_ADMIN\",\"comment\":\"e2e ceiling probe\"}")
check "a pillar admin cannot grant SUPER_ADMIN" "$esc" "403"
check "no qualification role carries case-data permissions" \
  "$(psql_iam "select count(*) from role_permissions rp join roles r on r.id=rp.role_id join permissions p on p.id=rp.permission_id where r.name in ('INSOLVENCY_PROFESSIONAL','CREDITOR') and p.resource='CLAIMS'")" "0"
check "SUPER_ADMIN holds every permission" \
  "$(psql_iam "select case when (select count(*) from permissions) = (select count(*) from role_permissions rp join roles r on r.id=rp.role_id where r.name='SUPER_ADMIN') then 'yes' else 'no' end")" "yes"
check "CLAIMS_WRITE was split into file and verify" \
  "$(psql_iam "select count(*) from permissions where name in ('CLAIMS_FILE','CLAIMS_VERIFY')")" "2"

echo "=== 10. Pillar linking and the SPI resolve contract ==="
# Never covered before, which is how three defects survived in the mock fixtures: pillar_id dropped
# at import, preferred_username never issued, and the container unable to reach the mock IdPs.
if curl -sf -m 3 http://127.0.0.1:9091/realms/ibbi-mock >/dev/null 2>&1; then
  linked=$(psql_user "select count(*) from pillar_links where pillar_type='IBBI'")
  if [ "$linked" -gt 0 ]; then
    check "a pillar link carries its external username" \
      "$(psql_user "select count(*) from pillar_links where pillar_type='IBBI' and external_username is not null and external_pillar_id is not null")" "$linked"
    check "the link is projected into iam for the login path" \
      "$(psql_iam "select count(*) from pillar_resolution where pillar_type='IBBI'")" "$linked"
    resolved=$(python3 "$(dirname "$0")/deploy/keycloak/resolve-probe.py" 2>/dev/null)
    check "the SPI resolve contract returns the linked user" "$resolved" "linked"
  else
    echo "         no pillar link present - run the linking handshake first; skipping"
  fi
else
  echo "         mock pillar IdPs are not running - skipping (docker compose up keycloak-*-mock)"
fi

echo "=== 11. Scoped visibility: a pillar admin sees only what they are related to ==="
sa_tok=$(kc -X POST --data-urlencode "client_id=ipie-service-template" \
  --data-urlencode "client_secret=ipie-service-template-secret" \
  --data-urlencode "username=superadmin@ipie.gov.in" --data-urlencode "password=ipie-superadmin-demo" \
  --data-urlencode "grant_type=password" http://keycloak:8080/realms/ipie/protocol/openid-connect/token \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
visible() { curl -s "$USER_SVC/api/v1/users?size=200" -H "Authorization: Bearer $1" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('totalElements','ERR'))" 2>/dev/null; }
sa_count=$(visible "$sa_tok")
pa_count=$(visible "$pa_tok")
# The pillar admin's four: themselves plus the three principals IBBI validated (user-service V10).
check "a pillar admin sees only their own pillar" "$pa_count" "4"
if [ "${sa_count:-0}" -gt "${pa_count:-0}" ]; then
  ok "a super admin sees more than a pillar admin ($sa_count vs $pa_count)"
else
  bad "a super admin should out-see a pillar admin (super=$sa_count pillar=$pa_count)"
fi
# No entity users leak into the pillar admin's view - the two axes are separate populations.
check "entity users are outside the pillar admin's scope" \
  "$(curl -s "$USER_SVC/api/v1/users?size=200" -H "Authorization: Bearer $pa_tok" \
     | python3 -c "import json,sys; print(sum(1 for u in json.load(sys.stdin).get('content',[]) if u['email'] in ('creditor.demo@ipie.gov.in','legalrep.demo@ipie.gov.in','authorizedrep.demo@ipie.gov.in')))" 2>/dev/null)" "0"
# Hierarchy: a parent reaches its child, a child never reaches its sibling or its parent.
check "a parent organisation reaches its child" \
  "$(psql_user "WITH RECURSIVE s(id) AS (SELECT id FROM organisations WHERE id='50000000-0000-0000-0000-000000000001' AND deleted_at IS NULL UNION SELECT o.id FROM organisations o JOIN s ON o.parent_id=s.id WHERE o.deleted_at IS NULL) SELECT count(*) FROM s")" "2"
check "a child reaches neither its parent nor its sibling" \
  "$(psql_user "WITH RECURSIVE s(id) AS (SELECT id FROM organisations WHERE id='50000000-0000-0000-0000-000000000002' AND deleted_at IS NULL UNION SELECT o.id FROM organisations o JOIN s ON o.parent_id=s.id WHERE o.deleted_at IS NULL) SELECT count(*) FROM s")" "1"

echo "=== 12. Scale properties (ids, search indexes, closure) ==="
# The registration in section 5 created a user after the switch to time-ordered ids. Version 4 rows
# remain from the seeds and are expected; what matters is that new rows are no longer random.
check "newly created ids are UUIDv7" \
  "$(psql_user "select case when substring(id::text,15,1)='7' then 'yes' else 'no' end from users order by created_at desc limit 1")" "yes"
# Counted across both tables since V13: the full-name index followed its column to `person`, so
# looking only at `users` would report a missing index rather than a moved one.
check "the trigram indexes exist for wildcard search" \
  "$(psql_user "select count(*) from pg_indexes where tablename in ('users','person') and indexname like '%trgm%'")" "3"
# Structure, not planner behaviour: on a table this small Postgres correctly prefers a sequential
# scan, so asserting the plan would fail here and pass only once the data is large - the opposite of
# useful. What must hold is that the indexes are GIN with trigram operator classes, which is what
# makes them usable for a leading wildcard at all; a btree on the same expression would not be.
check "the wildcard indexes are GIN + trigram" \
  "$(psql_user "select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid join pg_am a on a.oid=c.relam join pg_opclass o on o.oid=i.indclass[0] where (c.relname like 'idx_users_%_trgm' or c.relname like 'idx_person_%_trgm') and a.amname='gin' and o.opcname='gin_trgm_ops'")" "3"
# The closure table is a security answer, not a cache: if it disagrees with parent_id an
# administrator silently sees the wrong rows. Compare it against the tree it claims to describe.
check "the closure table agrees with parent_id" \
  "$(psql_user "with recursive walk(a,d) as (select id,id from organisations where deleted_at is null union select w.a,o.id from organisations o join walk w on o.parent_id=w.d where o.deleted_at is null) select (select count(*) from walk) - (select count(*) from organisation_closure)")" "0"

echo "=== 13. Rate limiting is enforced ==="
codes=""
for i in $(seq 1 7); do
  codes="$codes $(curl -s -o /dev/null -w '%{http_code}' -X POST $IAM_SVC/api/v1/credentials/password \
    -H 'Content-Type: application/json' -d '{"token":"probe-'$i'","password":"Str0ng!Passw0rd#1"}')"
done
echo "         codes:$codes"
if echo "$codes" | grep -q 429; then ok "the configured limit returns 429"; else bad "no 429 in:$codes"; fi

echo "=== 14. Nothing dead-lettered ==="
dlq=$(docker exec ipie-platform-mca-rabbitmq-1 rabbitmqctl list_queues name messages 2>/dev/null | awk '/dlq/ {print $2}' | head -1)
check "dead-letter queue empty" "${dlq:-0}" "0"

echo
echo "================ $pass passed, $fail failed ================"
exit $fail
