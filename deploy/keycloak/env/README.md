# Keycloak SPI configuration, per environment

`ipie-keycloak-spi` runs inside Keycloak's own process. It has no Spring context, no
`application.yml` and no profile mechanism, so everything it needs comes from **Keycloak's
environment** — which means the environment of whatever starts Keycloak, not of the services.

One file per environment, loaded into that process:

| Environment | File | Where Keycloak runs |
|---|---|---|
| `dev` | `spi.dev.env` | a developer's own machine — Windows host, Linux host, or a container; the platform does not care |
| `test` | `spi.test.env` | the shared test server |
| `uat` | `spi.uat.env` | the UAT server |
| `pre-prod` | `spi.pre-prod.env` | the pre-production server |
| `prod` | `spi.prod.env` | production |

## Loading one

```bash
# systemd unit for Keycloak
EnvironmentFile=/etc/ipie/spi.prod.env

# docker compose
env_file: [deploy/keycloak/env/spi.uat.env]

# a shell, before bin/kc.sh start
set -a; . deploy/keycloak/env/spi.dev.env; set +a
```

```powershell
# PowerShell, before bin\kc.bat start-dev  (dev on Windows)
Get-Content deploy\keycloak\env\spi.dev.env |
  Where-Object { $_ -and $_ -notmatch '^\s*#' } |
  ForEach-Object { $n,$v = $_ -split '=',2; Set-Item "Env:$n" $v }
```

## The rule these files encode

`SpiConfig` splits settings in two:

- **Platform constants** — the realm name, the SPI client id, the HMAC key ids. Identical
  everywhere, so they keep their built-in defaults and appear here only when overridden.
- **Environment facts** — every service URL and every secret. Defaults apply in `dev` **only**.
  Outside dev they are required, and Keycloak **refuses to start** without them, naming the
  variable.

That refusal is deliberate. Until 2026-08-13 the Keycloak base URL defaulted to `localhost:9090`,
which is Prometheus in the local stack: the SPI's token fetch got a 404 and every login failed
closed with `503 temporarily_unavailable`. A wrong endpoint is indistinguishable from an outage of
the service it points at. Failing at startup, by name, is the difference between a five-minute fix
and an afternoon.

`IPIE_KEYCLOAK_BASE_URL` is worth a note: the SPI runs *inside* Keycloak, so it is always localhost.
It is still required outside dev because the port and scheme are deployment choices, and getting
them wrong reproduces exactly the failure above.

## Secrets

**No file here carries a real secret except `spi.dev.env`,** whose values are local-development
shared secrets that also appear in `docker-compose.services.yml` and are worthless anywhere else.
Every other file marks secrets `__FROM_SECRET_STORE__`; the deployment injects them. Anything
matching `__FROM_SECRET_STORE__` reaching a running Keycloak means the injection did not happen —
and the startup check will say so, because a placeholder is not a valid HMAC key.

Each secret must match its counterpart on the receiving service:

| SPI variable | must equal | on |
|---|---|---|
| `IPIE_SPI_HMAC_SECRET` | `IPIE_SECURITY_HMAC_KEY_SPI_TO_IAM` | ipie-iam-service |
| `IPIE_SPI_HMAC_SECRET_USER` | `ipie.security.hmac.keys.spi-to-user` | ipie-user-service |
| `IPIE_KEYCLOAK_SPI_CLIENT_SECRET` | the `ipie-keycloak-spi` client secret | the realm |
