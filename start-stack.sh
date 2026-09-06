#!/usr/bin/env bash
# Bring up the iPIE local stack in the STANDARD topology, in one go, and refuse to start if the
# environment cannot support it.
#
#   ./start-stack.sh            preflight, then start infrastructure + the three services
#   ./start-stack.sh doctor     preflight only - change nothing, just report
#   ./start-stack.sh smoke      preflight + verify a real login end to end (stack must be up)
#   ./start-stack.sh stop       stop the three services (leaves infrastructure running)
#   ./start-stack.sh logs       tail the three service logs
#
# THE STANDARD LOCAL-DEVELOPMENT TOPOLOGY (see ARCHITECTURE_WORKING_PLAN.md §0):
#
#   the three services      Docker containers, publishing 8092 / 8093 / 8094
#   Keycloak                the HOST install at D:\keycloak-26.6.3 (kc.bat start-dev), :8080
#   Postgres + pgAdmin      the HOST, :5432
#   everything else         Docker, via docker-compose.yml
#
# This script is for a developer's machine and knows about Windows because that is where the
# developers are. It is NOT the deployed topology: test/uat/pre-prod/prod run Keycloak on a Linux
# server, configured from deploy/keycloak/env/spi.<environment>.env (ARCHITECTURE_WORKING_PLAN.md §0.1).
# Nothing in the platform assumes Windows - only this script does.
#
# Why the services must be containers: ipie-keycloak-spi's authenticators call ipie-iam-service
# from inside Keycloak's own process, which runs on Windows. A container publishes its port onto
# the host, so Keycloak reaches localhost:8093. A service started as a plain WSL process has no
# published port and the host cannot reach it at all - every SPI call then fails and the
# authenticator correctly fails closed with `503 temporarily_unavailable`, which reads exactly like
# a code bug and is not one.
#
# start-ipie.sh beside this file is the OTHER mode: it runs the services as host JVMs via bootRun,
# which is a faster edit loop but cannot serve the SPI. The two compete for 8092-8094; use one at a
# time. Use THIS script for anything touching login, credentials or stakeholder SSO.
set -uo pipefail

PLATFORM=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$PLATFORM")
INFRA_COMPOSE=$PLATFORM/docker-compose.yml
SERVICES_COMPOSE=$PLATFORM/docker-compose.services.yml

KC_PORT=8080
PG_PORT=5432
SERVICE_PORTS=(8092 8093 8094)

# Seeded demo account used by the smoke test. Its password lives in realm-export.json and its
# Argon2id hash in iam's V15 - the pair is what proves the whole login chain.
SMOKE_USER=superadmin@ipie.gov.in
SMOKE_PASS=ipie-superadmin-demo
SMOKE_CLIENT=ipie-service-template
SMOKE_SECRET=ipie-service-template-secret

RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BOLD=$'\e[1m'; OFF=$'\e[0m'
ok()   { printf "  ${GREEN}OK${OFF}    %s\n" "$1"; }
warn() { printf "  ${YELLOW}WARN${OFF}  %s\n" "$1"; }
fail() { printf "  ${RED}FAIL${OFF}  %s\n" "$1"; FAILED=$((FAILED+1)); }
step() { printf "\n${BOLD}%s${OFF}\n" "$1"; }

FAILED=0

# Where THIS SHELL reaches Keycloak - which is not necessarily where anything else reaches it.
#
# Keycloak's location is a deployment choice, not a property of the platform: a developer's laptop
# runs it on the Windows host, a Linux workstation runs it natively, and every deployed environment
# runs it on its own server. So resolve it rather than assuming, and keep the discovered value in
# KC_BASE for every later check.
#
# The awkward case is Windows + WSL in NAT networking: Keycloak binds the Windows side, and WSL's
# own `localhost` is a different machine, so `localhost:8080` fails here while Keycloak is perfectly
# healthy. WSL reaches the Windows host on the default-gateway address instead. (Keycloak dev mode
# must be started with --http-host=0.0.0.0 for that to work at all - it binds localhost otherwise.)
# None of this affects the SPI, which runs inside Keycloak's own process and always uses localhost.
KC_BASE=
KC_CANDIDATES=()
resolve_keycloak_base() {
  local gateway candidate
  gateway=$(ip route 2>/dev/null | awk '/^default/{print $3; exit}')
  KC_CANDIDATES=()
  [[ -n "${IPIE_KEYCLOAK_BASE_URL:-}" ]] && KC_CANDIDATES+=("$IPIE_KEYCLOAK_BASE_URL")
  KC_CANDIDATES+=("http://localhost:$KC_PORT")
  [[ -n "$gateway" ]] && KC_CANDIDATES+=("http://$gateway:$KC_PORT")

  for candidate in "${KC_CANDIDATES[@]}"; do
    if curl -fsS --max-time 5 "$candidate/realms/ipie/.well-known/openid-configuration" >/dev/null 2>&1; then
      KC_BASE=$candidate
      return 0
    fi
  done
  return 1
}

# --- preflight -----------------------------------------------------------------------------
#
# Every check here exists because it has actually gone wrong and cost someone an afternoon. The
# order matters: each one's failure makes the next check meaningless.

preflight() {
  step "1. Shell and Maven Local"

  # The build mounts $HOME/.m2/repository as a named build context (`additional_contexts: m2` in
  # docker-compose.services.yml). PowerShell does not set HOME, so ${HOME} expands to nothing, the
  # path collapses to .\.m2\repository relative to the compose file, and the build dies with
  # "failed to get build context m2". Windows' own %USERPROFILE%\.m2 is a different, usually empty
  # repository - setting HOME to it gets past the context error and then fails resolving
  # in.gov.ipie:ipie-parent inside the container instead. Run from WSL, where both are correct.
  if [[ -z "${WSL_DISTRO_NAME:-}" ]]; then
    fail "Not running under WSL. Run this from a WSL shell, not PowerShell or Git Bash."
    echo "        docker-compose.services.yml mounts \$HOME/.m2/repository as a build context;"
    echo "        PowerShell leaves HOME unset and the build fails with 'failed to get build context m2'."
  else
    ok "WSL shell ($WSL_DISTRO_NAME), HOME=$HOME"
  fi

  # The platform is consumed as published artifacts, never built from source by a service. If they
  # are missing the container build fails resolving in.gov.ipie:ipie-parent - after a long Gradle
  # download, so it is a slow way to find out.
  if [[ -d "$HOME/.m2/repository/in/gov/ipie/ipie-parent" ]]; then
    ok "platform artifacts present in $HOME/.m2/repository/in/gov/ipie"
  else
    warn "platform artifacts missing from Maven Local - publishing them now"
    (cd "$PLATFORM" && ./gradlew publishToMavenLocal -q) \
      && ok "published" || fail "publishToMavenLocal failed"
  fi

  # WSL2 mirrored networking silently disables Docker Desktop's port publishing onto Windows.
  # Everything looks healthy - containers up, WSL reaches every published port - while Windows
  # reaches none, so host-Keycloak's SPI cannot call iam and every login fails closed with
  # 503 temporarily_unavailable. Restarting Docker does not help; only removing the setting does.
  # Checked before Docker itself, because a failure here explains failures in checks 2 and 5.
  local wslconfig
  wslconfig=$(wslpath "$(powershell.exe -NoProfile -Command 'Write-Output $env:USERPROFILE' 2>/dev/null | tr -d '\r')" 2>/dev/null)/.wslconfig
  if [[ -f "$wslconfig" ]] && grep -qiE '^\s*networkingMode\s*=\s*mirrored' "$wslconfig"; then
    fail "WSL is in mirrored networking mode - Docker cannot publish ports to Windows"
    echo "        $wslconfig sets networkingMode=mirrored."
    echo "        Remove that line, then (from PowerShell, with this shell closed): wsl --shutdown"
    echo "        Restart Docker Desktop afterwards and re-run this script."
  else
    ok "WSL networking mode is not 'mirrored'"
  fi

  step "2. Docker"

  if docker info >/dev/null 2>&1; then
    ok "docker daemon reachable ($(docker version --format '{{.Server.Version}}' 2>/dev/null))"
  else
    fail "docker daemon not reachable from WSL - is Docker Desktop running with WSL integration on?"
    return
  fi

  step "3. Host services (Keycloak, Postgres)"

  # Two Keycloaks cannot share the H2 file: the second dies with "Database may be already in use
  # ... The file is locked". Ctrl-C on kc.bat also leaks the JVM - the batch wrapper dies and
  # java.exe keeps the lock - so a "stopped" Keycloak often is not.
  if resolve_keycloak_base; then
    ok "Keycloak answering at $KC_BASE with realm 'ipie'"
  else
    fail "Keycloak not answering on any of: ${KC_CANDIDATES[*]}"
    echo "        Windows host:  cd D:\\keycloak-26.6.3 ; bin\\kc.bat start-dev --http-host=0.0.0.0"
    echo "        Linux host:    bin/kc.sh start-dev"
    echo "        --http-host=0.0.0.0 matters on Windows: dev mode otherwise binds localhost only,"
    echo "        which WSL cannot reach. It is not needed by the SPI, which runs inside Keycloak."
    echo "        If it will not start with 'The file is locked', a previous JVM still holds the H2"
    echo "        database: Get-Process java | Stop-Process, then start it again."
  fi

  # Postgres is on the host; containers reach it as host.docker.internal, which is what the
  # services' SPRING_DATASOURCE_URL uses. Test it the same way the services will.
  if docker run --rm --add-host=host.docker.internal:host-gateway postgres:16-alpine \
       pg_isready -h host.docker.internal -p $PG_PORT -q >/dev/null 2>&1; then
    ok "Postgres reachable from a container at host.docker.internal:$PG_PORT"
  else
    fail "Postgres not reachable at host.docker.internal:$PG_PORT from inside a container"
    echo "        It runs on the Windows host. Check the service is up and listening on $PG_PORT."
  fi

  # The SPI signs its calls to iam with this secret and iam verifies them against
  # IPIE_SECURITY_HMAC_KEY_SPI_TO_IAM. They must be the same string. Keycloak is a host process, so
  # its environment cannot be read from here - but a mismatch shows up as a failed smoke login,
  # which is why `smoke` exists. Surface the value iam expects so it can be compared by eye.
  local expected
  expected=$(grep -oP 'IPIE_SECURITY_HMAC_KEY_SPI_TO_IAM:\s*\K\S+' "$SERVICES_COMPOSE" 2>/dev/null | head -1)
  if [[ -n "$expected" ]]; then
    ok "iam expects SPI HMAC secret: $expected"
    echo "        Keycloak's own environment must carry the identical value as IPIE_SPI_HMAC_SECRET."
    echo "        In the PowerShell window you start Keycloak from, BEFORE bin\\kc.bat start-dev:"
    echo "          \$env:IPIE_SPI_HMAC_SECRET = \"$expected\""
    echo "        'Set VAR=value' is cmd syntax and silently does nothing in PowerShell."
  fi

  # The SPI also has to be told where Keycloak itself is, because it fetches a client-credentials
  # token before every signed call. Its built-in default pointed at :9090 until 2026-08-13 - which is
  # Prometheus in this stack - so the token fetch got a 404 and every login failed closed with
  # 503 temporarily_unavailable, indistinguishable from the port-forwarding and HMAC faults above.
  # The default is now :8080 and correct for the standard topology; this only bites if a stale
  # ipie-keycloak-spi.jar is deployed, or if the variable is set to something wrong.
  echo "        Everything else the SPI reads is in $PLATFORM/deploy/keycloak/env/spi.dev.env,"
  echo "        which is also the shape every deployed environment's file takes. Load it before"
  echo "        starting Keycloak (the defaults match it, so a laptop works without this):"
  echo "          Get-Content deploy\\keycloak\\env\\spi.dev.env |"
  echo "            Where-Object { \$_ -and \$_ -notmatch '^\\s*#' } |"
  echo "            ForEach-Object { \$n,\$v = \$_ -split '=',2; Set-Item \"Env:\$n\" \$v }"
  echo "        Outside dev those settings are REQUIRED and Keycloak refuses to start without them."

  # A jar predating the port fix reintroduces the 9090 default no matter what the source says.
  local deployed_jar=D:/keycloak-26.6.3/providers/ipie-keycloak-spi.jar
  local mounted_jar=/mnt/d/keycloak-26.6.3/providers/ipie-keycloak-spi.jar
  if [[ -f "$mounted_jar" ]]; then
    # python3 rather than unzip: the smoke test already depends on python3, and unzip is not
    # installed on a default Ubuntu-on-WSL.
    python3 -c "
import sys, zipfile
try:
    b = zipfile.ZipFile(sys.argv[1]).read('in/gov/ipie/keycloak/spi/credential/CredentialVerifyClient.class')
except Exception:
    sys.exit(2)
sys.exit(0 if b'localhost:9090' in b else 1)" "$mounted_jar" 2>/dev/null
    case $? in
      0)
        fail "the deployed SPI jar still carries the localhost:9090 Keycloak default"
        echo "        $deployed_jar predates the 2026-08-13 fix. Every login will fail closed with"
        echo "        503 temporarily_unavailable unless IPIE_KEYCLOAK_BASE_URL is set explicitly."
        echo "        Rebuild and redeploy:"
        echo "          (cd $ROOT/ipie-iam-service && ./gradlew :keycloak-spi:jar)"
        echo "          cp ipie-iam-service/keycloak-spi/build/libs/ipie-keycloak-spi.jar $mounted_jar"
        echo "        then restart Keycloak - providers are loaded once, at startup."
        ;;
      1) ok "deployed SPI jar carries the corrected Keycloak base URL default" ;;
      *) warn "could not read CredentialVerifyClient from $mounted_jar - is it a valid SPI jar?" ;;
    esac
  else
    warn "no SPI jar found at $mounted_jar - the authenticators cannot load"
  fi
}

# Docker Desktop must forward published container ports onto Windows, because host Keycloak reaches
# iam at localhost:8093. This breaks in a way nothing else notices: WSL still reaches the ports, so
# every container looks healthy, while the SPI gets nothing and every login returns
# `503 temporarily_unavailable`. `wsl --shutdown` is a known trigger.
check_windows_port_forwarding() {
  step "5. Windows can reach the published ports"

  if ! command -v powershell.exe >/dev/null 2>&1; then
    warn "powershell.exe not callable from WSL - cannot verify Windows-side port forwarding"
    return
  fi

  local port result
  for port in "${SERVICE_PORTS[@]}"; do
    result=$(powershell.exe -NoProfile -Command \
      "if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }" 2>/dev/null | tr -d '\r\n ')
    if [[ "$result" == "yes" ]]; then
      ok "Windows is listening on $port"
    else
      fail "Windows is NOT listening on $port - Docker Desktop is not forwarding published ports"
      echo "        WSL can still reach them, so the containers look fine and login fails anyway:"
      echo "        Keycloak runs on Windows and cannot reach iam, so the SPI fails closed and the"
      echo "        token endpoint returns 503 temporarily_unavailable."
      echo "        Fix: restart Docker Desktop on Windows, then re-run this script."
      echo "        (Commonly caused by 'wsl --shutdown' while Docker Desktop was running.)"
      return
    fi
  done
}

wait_for_health() {
  step "6. Waiting for the services to report healthy"
  local port name deadline
  for port in "${SERVICE_PORTS[@]}"; do
    case $port in
      8092) name=ipie-user-service ;;
      8093) name=ipie-iam-service ;;
      8094) name=ipie-communication-service ;;
    esac
    deadline=$((SECONDS + 240))
    printf "  %-28s " "$name ($port)"
    while (( SECONDS < deadline )); do
      if curl -fsS --max-time 3 "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        printf "${GREEN}UP${OFF}\n"; break
      fi
      sleep 3
      printf "."
    done
    if (( SECONDS >= deadline )); then
      printf "${RED}TIMEOUT${OFF}\n"
      FAILED=$((FAILED+1))
      echo "        docker compose -f $SERVICES_COMPOSE logs $name"
    fi
  done
}

# The only check that exercises the whole chain: ipie-web's grant -> Keycloak -> the SPI's
# HMAC-signed call to iam -> Argon2id verify against user_credentials -> token issued. If this
# passes, the stack is genuinely working; if it fails, the message says which link broke.
smoke_login() {
  step "7. End-to-end login smoke test"
  # `smoke` is also a standalone subcommand, so resolve Keycloak if preflight has not already.
  if [[ -z "$KC_BASE" ]] && ! resolve_keycloak_base; then
    fail "Keycloak not reachable at any of: ${KC_CANDIDATES[*]} - cannot run the smoke test"
    return
  fi
  local body
  body=$(curl -s --max-time 20 \
    --data-urlencode "client_id=$SMOKE_CLIENT" \
    --data-urlencode "client_secret=$SMOKE_SECRET" \
    --data-urlencode "username=$SMOKE_USER" \
    --data-urlencode "password=$SMOKE_PASS" \
    --data-urlencode "grant_type=password" \
    "$KC_BASE/realms/ipie/protocol/openid-connect/token")

  if grep -q access_token <<<"$body"; then
    local perms
    perms=$(python3 -c "
import sys,json,base64
d=json.loads(sys.stdin.read()); t=d['access_token'].split('.')[1]
c=json.loads(base64.urlsafe_b64decode(t+'='*(-len(t)%4)))
print(','.join(sorted(c.get('permissions',[]))))" <<<"$body" 2>/dev/null)
    ok "login succeeded as $SMOKE_USER"
    echo "        permissions: ${perms:-<none>}"
    return
  fi

  if grep -q temporarily_unavailable <<<"$body"; then
    fail "login returned 503 temporarily_unavailable - the SPI could not get an answer from iam"
    echo "        This is the authenticator failing CLOSED, not a wrong password. Usual causes:"
    echo "          - Docker Desktop is not forwarding ports to Windows (check 5 above)"
    echo "          - Keycloak's IPIE_SPI_HMAC_SECRET is unset or differs from iam's"
    echo "          - IPIE_KEYCLOAK_BASE_URL points somewhere that is not Keycloak, or a stale SPI"
    echo "            jar still defaults it to :9090 (Prometheus) - the SPI fetches a"
    echo "            client-credentials token first, and a 404 there never reaches iam at all"
    echo "          - the SPI service account lost CREDENTIAL_VERIFY in the realm"
    echo "        Keycloak's own log names which of these it was - kc.bat start-dev prints the"
    echo "        CredentialVerificationException with its cause."
  elif grep -q invalid_grant <<<"$body"; then
    fail "login returned invalid_grant - iam answered, and said the password is wrong"
    echo "        The chain works; the credential does not. Check iam's user_credentials seed (V15)."
  else
    fail "login failed: $(head -c 200 <<<"$body")"
  fi
}

case "${1:-start}" in
  doctor)
    preflight
    check_windows_port_forwarding
    ;;
  smoke)
    smoke_login
    ;;
  stop)
    docker compose -f "$SERVICES_COMPOSE" down
    echo "Services stopped. Infrastructure and host Keycloak/Postgres left running."
    exit 0
    ;;
  logs)
    docker compose -f "$SERVICES_COMPOSE" logs -f
    exit 0
    ;;
  start)
    preflight
    if (( FAILED > 0 )); then
      printf "\n${RED}%d preflight check(s) failed - not starting.${OFF} Fix the above, then re-run.\n" "$FAILED"
      exit 1
    fi

    step "4. Starting infrastructure (RabbitMQ, Redis, Elasticsearch, MinIO, MailHog, observability)"
    # Compose's own `keycloak` service sits behind the "keycloak" profile and is deliberately NOT
    # started: it would publish 8080 and compete with the host install that owns the realm.
    docker compose -f "$INFRA_COMPOSE" up -d || { echo "infrastructure failed to start"; exit 1; }

    step "4b. Building and starting the three services"
    docker compose -f "$SERVICES_COMPOSE" up -d --build || { echo "services failed to start"; exit 1; }

    check_windows_port_forwarding
    wait_for_health
    smoke_login

    if (( FAILED > 0 )); then
      printf "\n${YELLOW}Stack is up but %d check(s) failed - see above.${OFF}\n" "$FAILED"
      exit 1
    fi
    printf "\n${GREEN}${BOLD}Stack is up and login works.${OFF}\n"
    echo "  services   8092 user | 8093 iam | 8094 communication"
    echo "  Keycloak   http://localhost:8080  (host install)"
    echo "  RabbitMQ   http://localhost:15672 (guest/guest)   MailHog http://localhost:8025"
    ;;
  *)
    echo "usage: $0 [start|doctor|smoke|stop|logs]"; exit 2
    ;;
esac
