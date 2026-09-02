# Keycloak providers

Drop `ipie-keycloak-spi.jar` here before `docker compose up`. Keycloak loads every jar in this
directory as a provider; the pillar-SSO first-broker-login Authenticator lives in this one.

It is **built in a different repository** as of 2026-08-09:

    cd ../ipie-iam-service          # or wherever you cloned it
    ./gradlew :keycloak-spi:jar
    cp keycloak-spi/build/libs/ipie-keycloak-spi.jar \
       ../ipie-platform-mca/deploy/keycloak/providers/

The jar is gitignored here — it is a build output, not source.

## Why a copy rather than a direct mount

`docker-compose.yml` used to mount `./ipie-keycloak-spi/build/libs/ipie-keycloak-spi.jar`
directly, which worked while the SPI was a module of this repository. After the extraction that
path no longer existed, and Docker silently created an **empty directory** at the mount point
instead of failing: Keycloak started cleanly, loaded no provider, and pillar SSO was broken
with nothing in any log to say so.

Mounting this directory instead makes the absence visible — the stack starts with no provider and
the flow fails at the first brokered login, rather than pretending to work. Reaching across into a
sibling repository's `build/` directory would also reintroduce exactly the coupling the split
removed.
