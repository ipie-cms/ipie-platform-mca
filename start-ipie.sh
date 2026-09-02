#!/usr/bin/env bash
# Bring up the whole iPIE local stack in one go.
#
#   ./start-ipie.sh          start everything
#   ./start-ipie.sh stop     stop the host-run services (leaves infra + Keycloak running)
#   ./start-ipie.sh logs     tail all three service logs
#
# Runs the services as host JVMs via bootRun, which is the faster loop while developing. To run
# them as containers instead, use docker-compose.services.yml beside this file - the two compete
# for ports 8092-8094, so use one at a time.
#
# Layout this assumes: the service repositories are checked out beside this one (both paths are
# derived from this script's location, not hardcoded), Postgres runs on the Windows host on 5432,
# and Keycloak is the host install at D:\keycloak-26.6.3 rather than the containerised one - which
# is why the infrastructure list below deliberately excludes compose's own `keycloak` service: it
# publishes 8080 and would collide with the host instance.
set -uo pipefail

# Derived from this script's own location rather than hardcoded: this file lives in the platform
# repository, and the service repositories are checked out beside it. Logs stay outside the
# repository so a run never dirties the working tree.
PLATFORM=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$PLATFORM")
LOGS=$ROOT/.ipie-logs
KC_URL=http://keycloak:8080
REALM=ipie
WEB_ORIGIN=http://localhost:5173

# Every service reads these. The issuer must be the hostname Keycloak actually stamps into its
# tokens (conf/keycloak.conf sets hostname=http://keycloak:8080, and `keycloak` is mapped to
# 127.0.0.1 in both the Windows and WSL hosts files) - point these at localhost instead and every
# request fails JWT validation with a confusing "invalid issuer".
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$KC_URL/realms/$REALM"
export IPIE_KEYCLOAK_BASE_URL="$KC_URL"
# Empty by default, which makes every browser call fail preflight with "Invalid CORS request"
# while curl still works - the confusing failure mode this line exists to prevent.
export IPIE_CORS_ALLOWED_ORIGINS="$WEB_ORIGIN"

# Shared secret for HMAC-signed inter-service calls. It has no default anywhere on purpose, but
# signing is enabled by default - so without this every user-service -> iam-service call fails with
# "IllegalArgumentException: Empty key" and a 500, which breaks registration completion, the only
# cross-service write path. Both the signing and verifying side must carry the same value. Local
# development value only; real environments take it from the secrets manager.
export IPIE_SECURITY_HMAC_KEY_USER_TO_IAM=local-dev-user-to-iam-shared-secret

# Unset by default, which silently downgrades event publishing to a logging implementation: the
# outbox fills, nothing is delivered, and anything waiting on an event (the registration OTP email,
# default role assignment) never happens. localhost here because these services run on the host,
# and the broker publishes 5672 there.
export SPRING_RABBITMQ_HOST=localhost

# IPIE_OTLP_ENDPOINT is deliberately not set: the default is http://localhost:4318/v1/traces, and
# the collector publishes 4317/4318 on the host, so host-run services already reach it. Containers
# are the case that needs an override - see docker-compose.services.yml.

# InterServiceClient resolves a target by name through ipie.client.base-url-pattern, which defaults
# to http://{service}:8080 - a Docker Compose / Kubernetes DNS name. Running on the host, that name
# does not resolve and every inter-service call dies with UnresolvedAddressException, so each target
# needs an explicit host:port. The pattern alone cannot express this because the services sit on
# different ports here. SPRING_APPLICATION_JSON is used rather than an env var per entry: the
# property is a Map with hyphenated keys, which cannot be expressed as a shell variable name.
export SPRING_APPLICATION_JSON='{"ipie":{"client":{"services":{
  "ipie-user-service":"http://localhost:8092",
  "ipie-iam-service":"http://localhost:8093",
  "ipie-communication-service":"http://localhost:8094"}}}}'

# Postgres runs on the Windows host, not in compose (the compose `postgres:` block is commented
# out for this reason). WSL reaches it on 127.0.0.1 because this machine uses mirrored networking;
# on default NAT networking this would need the host IP from /etc/resolv.conf instead.
PG_HOST=${PG_HOST:-127.0.0.1}
PG_PORT=${PG_PORT:-5432}
export SPRING_DATASOURCE_USERNAME=${PG_USER:-postgres}
export SPRING_DATASOURCE_PASSWORD=${PG_PASS:-postgres}

# service:port:database. ipie-user-service and ipie-iam-service deliberately share one physical
# database and schema - iam keeps its own Flyway history table (flyway_schema_history_iam) so the
# two migration timelines stay independent. SPRING_DATASOURCE_URL is therefore set per service,
# never exported globally, or all three would land in the same database.
SERVICES=(
  "ipie-user-service:8092:ipie_user_service"
  "ipie-iam-service:8093:ipie_user_service"
  "ipie-communication-service:8094:ipie_communication_service"
)

# The full infrastructure stack minus `keycloak` (see the note above).
INFRA=(redis rabbitmq mailhog elasticsearch minio minio-init clamav opa otel-collector jaeger prometheus grafana pgadmin)

case "${1:-start}" in
stop)
  # Only ever kills the Gradle/JVM processes this script started. Matching on "whatever listens on
  # this port" is not safe: when the services run as containers instead (see
  # docker-compose.services.yml) the listener is Docker's proxy, and killing that breaks the
  # container rather than stopping a service.
  for entry in "${SERVICES[@]}"; do
    IFS=: read -r name port _db <<<"$entry"
    pid=$(ss -ltnp 2>/dev/null | grep ":$port " | grep -oP 'pid=\K[0-9]+' | head -1)
    if [ -n "$pid" ] && tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -q "$name"; then
      echo "  stopping $name (pid $pid)"
      kill "$pid"
    elif docker ps --format '{{.Ports}}' 2>/dev/null | grep -q ":$port->"; then
      echo "  $name is running as a container on :$port - stop it with"
      echo "      docker compose -f $PLATFORM/docker-compose.services.yml down"
    fi
  done
  pkill -f 'GradleWrapperMain|bootRun' 2>/dev/null
  echo "  host-run services stopped (infra and Keycloak left running)"
  exit 0
  ;;
logs)
  tail -f "$LOGS"/*.log
  exit 0
  ;;
esac

mkdir -p "$LOGS"

echo "── 0/4  Postgres on the host ($PG_HOST:$PG_PORT)"
if timeout 5 bash -c "cat < /dev/null > /dev/tcp/$PG_HOST/$PG_PORT" 2>/dev/null; then
  echo "  reachable"
else
  echo "  NOT reachable - start the Windows PostgreSQL service first:"
  echo "      powershell.exe -Command \"Start-Service postgresql-x64-18\""
  exit 1
fi

echo
echo "── 1/4  infrastructure (docker compose, excluding keycloak)"
docker compose -f "$PLATFORM/docker-compose.yml" up -d "${INFRA[@]}" 2>&1 | tail -5

echo
echo "── 2/4  Keycloak on :8080"
if curl -sf -m 5 "$KC_URL/realms/$REALM" >/dev/null 2>&1; then
  echo "  already running"
else
  echo "  starting the host install (D:\\keycloak-26.6.3)…"
  powershell.exe -ExecutionPolicy Bypass -File 'D:\keycloak-26.6.3\start-ipie-keycloak.ps1' \
    >"$LOGS/keycloak.log" 2>&1 &
  for _ in $(seq 1 60); do
    curl -sf -m 3 "$KC_URL/realms/$REALM" >/dev/null 2>&1 && break
    sleep 3
  done
  curl -sf -m 3 "$KC_URL/realms/$REALM" >/dev/null 2>&1 \
    && echo "  up" || { echo "  FAILED - see $LOGS/keycloak.log"; exit 1; }
fi

echo
echo "── 3/4  services"
for entry in "${SERVICES[@]}"; do
  IFS=: read -r name port db <<<"$entry"
  if [ ! -d "$ROOT/$name" ]; then echo "  $name: directory missing, skipped"; continue; fi
  echo "  starting $name on :$port  (db: $db, log: $LOGS/$name.log)"
  ( cd "$ROOT/$name" \
      && SERVER_PORT=$port \
         SPRING_DATASOURCE_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$db" \
         ./gradlew bootRun --console=plain -q ) >"$LOGS/$name.log" 2>&1 &
done

echo
echo "── 4/4  waiting for health (first run compiles, so this can take a few minutes)"
deadline=$((SECONDS + 900))
while [ $SECONDS -lt $deadline ]; do
  allup=1
  for entry in "${SERVICES[@]}"; do
    IFS=: read -r _n port _d <<<"$entry"
    [ -d "$ROOT/${entry%%:*}" ] || continue
    [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://localhost:$port/actuator/health")" = "200" ] || allup=0
  done
  [ $allup -eq 1 ] && break
  sleep 10
done

echo
for entry in "${SERVICES[@]}"; do
  IFS=: read -r name port _db <<<"$entry"
  [ -d "$ROOT/$name" ] || continue
  code=$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://localhost:$port/actuator/health")
  [ "$code" = "200" ] && echo "  ✅ $name  :$port" || echo "  ❌ $name  :$port  (HTTP $code - see $LOGS/$name.log)"
done
echo "  ✅ keycloak  :8080"
echo
echo "  frontend:  cd $ROOT/ipie-web && npm run dev     → $WEB_ORIGIN"
echo "  stop:      $0 stop          logs: $0 logs"
