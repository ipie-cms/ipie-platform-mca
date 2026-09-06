# iPIE — Troubleshooting

Every failure that has cost real time on this platform, with the cause and the fix that worked.

Organised by **symptom**, because that is what you have when you arrive. Nearly all of these look
like application bugs and are not — that is precisely why they are worth writing down. If you solve
something that is not here, add it: the entry costs ten minutes and saves the next person a day.

Entries marked *(DevEnv §n)* were consolidated from the inline "Problem?" notes in the Development
Environment Configuration document. They remain there too, beside the step that provokes them, which
is where they are most useful while following a procedure; this file is where you look when you are
not following one.

---

## Repository and workspace

### `git pull` refuses because of local changes *(DevEnv §19.1, §26.3.1)*

`git stash`, then `git pull`, then `git stash pop`.

**Do not** reach for `git checkout .` or `git reset --hard` first. Both discard uncommitted work
silently, and on this platform that has meant losing migrations and configuration that existed
nowhere else. Run `git status` before either, always.

### `grep -rl` prints nothing, or `sed` reports "no such file", while renaming a service *(DevEnv §19.2)*

Either the working directory is not the repository root, or `SERVICE_NAME` is not exported in this
shell. **Export statements do not survive a new terminal**, so a procedure resumed in a second window
silently operates on an empty variable. Re-run the exports and check `pwd` before retrying.

### `jq: command not found` *(DevEnv §19.8)*

Not installed here, which is why every documented command uses `python3 -c "import sys,json; ..."`
instead. Install it if you prefer: `sudo apt-get update && sudo apt-get install -y jq`.

---

## Login and Keycloak

### The token endpoint returns `unknown_error` and iam records no request at all

**Cause.** Keycloak was started without the SPI environment loaded. The credential SPI signs its call
to ipie-iam-service with a shared key and has no usable default for it, deliberately: an empty key
fails closed, because the alternative is a login succeeding unverified. The signer throws before any
request is made, so the failure is entirely inside Keycloak.

**How to confirm.** `docker logs ipie-services-ipie-iam-service-1 | grep credentials/verify` — no
matches means Keycloak never called the platform, so nothing in the platform can be the cause.

**Fix.** Start Keycloak with `deploy/keycloak/start-keycloak.ps1`, or load the environment by hand
before `kc.bat`:

```powershell
Get-Content "<repo>\ipie-platform-mca\deploy\keycloak\env\spi.dev.env" |
  Where-Object { $_ -and $_ -notmatch '^\s*#' } |
  ForEach-Object { $n,$v = $_ -split '=',2; Set-Item "Env:$n" $v }
bin\kc.bat start-dev --http-host=0.0.0.0
```

### Every login fails with `503 temporarily_unavailable`

The SPI's fail-closed path: it could not reach iam. Three distinct causes have produced it.

1. **A service was started as a host or WSL JVM instead of a container.** `./gradlew bootRun`
   publishes no port, so Keycloak — which runs on the host — cannot reach it on `localhost:8093` or
   on the WSL IP. Run the services as containers; that is what the standard topology is for.
2. **`IPIE_KEYCLOAK_BASE_URL` was unset and defaulted to `localhost:9090`**, which in this stack is
   Prometheus. The client-credentials fetch 404s against it. Fixed at source (the default is now
   `:8080`), and `SpiConfig` validates at startup — but an old SPI jar in `providers/` can still
   carry the original. `start-stack.sh` checks the deployed jar for it.
3. **Docker was publishing no ports onto Windows** — see the WSL section below.

### Keycloak answers from containers but not from WSL

Dev mode binds `localhost` only, and WSL's `localhost` is a different machine. Start it with
`--http-host=0.0.0.0`. Until you do, every token call has to be driven from inside a container:

```bash
docker run --rm --add-host=keycloak:host-gateway curlimages/curl:latest -s ...
```

### `kc.bat` refuses to start: "The file is locked"

A previous JVM still holds the H2 database that dev mode keeps the realm in.
`Get-Process java | Stop-Process`, then start again. `start-keycloak.ps1` refuses to start a second
instance for this reason rather than letting it fail confusingly.

### Keycloak has no log to read

`kc.bat` logs to the console only, so a crash in a window that has since been closed leaves nothing.
`start-keycloak.ps1` redirects the console to `keycloak-console.log`.

### A permission is enforced but every call returns 403

The permission exists in the database but not as a realm role, or is not granted to the service
account. `RoleService` pushes only a role's **name** to Keycloak, never its `role_permissions` rows,
so a database-side grant does not appear in an issued token by itself — the realm role and its
composite must be mirrored in `deploy/keycloak/realm-export.json`. This is how `CREDENTIAL_VERIFY`
came to be enforced-but-ungranted and returned 403 on every login.

### Re-importing the realm silently wipes user role mappings

A partial import with `OVERWRITE` replaces users wholesale. Use the targeted role-mapping endpoints
instead of re-importing the realm when only roles need changing.

---

## Docker and WSL

### Containers are healthy, WSL reaches their ports, Windows reaches none

**Cause.** `%USERPROFILE%\.wslconfig` contains `networkingMode=mirrored`, which is incompatible with
Docker Desktop port publishing. Container-to-host still works, so migrations run normally and only
host-to-container traffic — including Keycloak's SPI calls — fails. It reads exactly like a code bug.

**Fix.** Remove the line, `wsl --shutdown`, restart Docker Desktop. Restarting Docker alone does not
fix it. Note that ending "Docker Desktop" in Task Manager kills only the GUI; `com.docker.backend`
owns port forwarding and survives.

### A container fails to start with exit 127 and a bind-mount error

After a Docker Desktop restart, a cached bind-mount entry can go stale:
`mount ... /run/desktop/mnt/host/wsl/docker-desktop-bind-mounts/...: not a directory`. `docker start`
can never repair it. Use `docker compose -f docker-compose.yml up -d --force-recreate <service>`.

### `docker compose` fails on the build context from PowerShell

`docker-compose.services.yml` mounts `${HOME}/.m2/repository` as a build context. PowerShell leaves
`HOME` unset, so the context resolves to nothing. **Run compose from WSL**, not PowerShell: setting
`HOME` in PowerShell gets past the first error and then fails to resolve `in.gov.ipie:ipie-parent`,
because the platform artifacts are published into WSL's `~/.m2`.

### A container keeps restarting, or exits immediately *(DevEnv §19.8)*

`docker compose logs <service> --tail 200` first, then `docker compose ps` for the exit status. The
log almost always names the cause; the exit code rarely does.

**Do not run `docker compose down -v` to clear it.** The `-v` wipes the Postgres data volume, which
takes every database with it. That is occasionally what you want — it is never what you want by
accident, in the middle of diagnosing something else.

### WSL consumes enough memory to stall the IDE

Give Docker Desktop's WSL2 backend an explicit ceiling in `%USERPROFILE%\.wslconfig` — 8–12 GB on a
16 GB machine. The default lets WSL take as much as it likes.

### The `docker-desktop` WSL distro wedges

`dockerd` gone while orphaned `containerd-shim` processes linger: `wsl --terminate docker-desktop`
recycles just that distro, leaving your Ubuntu session — and anything running in it — alive.

---

## Services and endpoints

### A health check or the Prometheus scrape returns 401 *(DevEnv §19.8)*

The path is not in `IpieSecurityProperties.publicPaths` (ipie-common-libs, security package). This is
exactly what happened when Prometheus support was first added, and it is the first thing to check
whenever a new actuator-style endpoint starts refusing unauthenticated calls.

---

## Database and Flyway

### `Found non-empty schema(s) "public" but no schema history table`

**Cause.** ipie-user-service and ipie-iam-service share one database and schema (decision D6), each
with its own history table. Whichever starts first creates its tables there, so the second finds a
non-empty schema and refuses. It only appears against a freshly created database, which is why it
can hide for months.

**Fix.** Both services set `baseline-on-migrate: true` **and `baseline-version: 0`**. The version
matters: the default of 1 would mark `V1` as already applied and skip the baseline schema entirely,
so the service would start against tables that were never created.

### `must be owner of extension pgcrypto`

A migration contains `COMMENT ON EXTENSION`, which requires owning the extension — the migration
role does not, and should not. `pg_dump` emits it next to `CREATE EXTENSION`, so it arrives whenever
a baseline is generated from a dump. Delete the comment; keep the `CREATE EXTENSION IF NOT EXISTS`,
which is a harmless no-op when the extension is already installed.

*Verify baselines as the migration role, not as a superuser.* A scratch check that runs as `postgres`
will not catch this class of problem at all.

### `password authentication failed for user "postgres"` from Flyway

The Flyway credentials fell back to a literal `postgres/postgres`. The fallback must resolve the
datasource **property**, not the environment variable behind it:

```yaml
user: ${SPRING_FLYWAY_USER:${spring.datasource.username}}
```

Referencing `SPRING_DATASOURCE_USERNAME` breaks whenever the credentials come from anywhere else — a
test's dynamic properties, a config server. The quiet version is worse than the loud one: where a
superuser does accept that password, migrations run as the superuser and undo the owner/app
separation they exist to create.

### Flyway refuses to start after migrations were rewritten

Expected: the recorded versions and checksums no longer match the files. On a development database,
drop and recreate. The databases are owned by `postgres`, but **each table is owned by that
service's `_owner` role**, so no superuser is needed:

```sql
DO $$ DECLARE t record; BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='public' AND tableowner = current_user
  LOOP EXECUTE format('DROP TABLE IF EXISTS public.%I CASCADE', t.tablename); END LOOP;
END $$;
```

Run it once per owner role — each drops only its own tables, which is the boundary working.

### A new service fails at migration with "database does not exist" *(DevEnv §19.5)*

The init script was not executable. **Postgres silently skips non-executable files** in
`/docker-entrypoint-initdb.d/`, so the database is simply never created and the failure surfaces
later, in Flyway, pointing at the wrong thing. `chmod +x` the script. This is the single most common
failure when adding a service.

### The database still does not appear after adding the init script *(DevEnv §19.5)*

Scripts in `/docker-entrypoint-initdb.d/` run **once**, when the volume is first created. An existing
volume will never run them. Create the database by hand against the running instance, or recreate the
volume knowingly — see the warning about `down -v` below.

### A service can read another service's tables

Grants were never applied, or were applied as "all tables in schema". Under D6 a blanket grant hands
ipie-user-service the credential tables it exists not to see. Grants are written out table by table
in each service's grant migration, deliberately, so that adding one is a reviewable act.

---

## Build and platform artifacts

### A change to `checkstyle.xml` or `spotbugs-exclude.xml` appears to do nothing

Two independent causes, and it is usually the first:

1. **`ipie-build-conventions` is an `includeBuild`.** The platform root's `publishToMavenLocal` does
   **not** publish it. Publish it from its own directory:
   `cd ipie-build-conventions && ../gradlew publishToMavenLocal`.
2. The consuming build used to extract the packaged config only when the destination did not already
   exist, so a service kept its first extraction forever. Fixed — extraction is now unconditional —
   but an old plugin version still behaves the old way, in which case run a clean.

### A service will not compile against the platform after a platform change

Since 2026-08-31 a service pins a **released** platform version in its own `gradle.properties`
(`ipiePlatformVersion`, currently `0.1.0`), resolved from `mavenLocal()` first. So the old symptom —
compiling against whatever was last published locally, including uncommitted platform work — is
gone, and with it the reason two machines on the same commit built differently.

The failure mode it was replaced by: a service does **not** see a platform change until someone
cuts a release and bumps the pin. If a service fails on a method that does exist in the platform
source, the pin is behind, not the publish. Republishing will not help; the sequence is

1. set `version=<next>` in `ipie-platform-mca/gradle.properties`
2. `./gradlew publishToMavenLocal` **and** `./gradlew -p ipie-build-conventions publishToMavenLocal`
   — the root task silently skips the included build, and `ipie-quality-config` ships inside that
   plugin's jar, so a Checkstyle/SpotBugs config change reaches nothing until this second command
   runs
3. bump `ipiePlatformVersion` in each service
4. move the platform tree on to the next `-SNAPSHOT`

`-Pversion=` overrides step 1 for the root build but **not** for `ipie-build-conventions`, which
reads the root `gradle.properties` file directly so that the version stays declared in one place.
Edit the file.

### `JAVA_HOME is not set and no 'java' command could be found` *(DevEnv §19.4, §21)*

This WSL host has no JDK installed directly. Either install one —
`sudo apt-get update && sudo apt-get install -y openjdk-21-jdk`, which needs a real interactive
terminal for the sudo prompt and therefore does not work from an automated shell — or run Gradle
inside the same JDK image the Dockerfile uses, which installs nothing:

```bash
docker run --rm -v "$PWD":/w -w /w -v "$HOME/.gradle":/root/.gradle \
  eclipse-temurin:21-jdk ./gradlew build
```

### `./gradlew check` fails on SpotBugs `EI_EXPOSE_REP2` for an injected collaborator

Expected for a Spring service: the finding fires on any constructor that stores a reference it was
given. The exclusion list in `ipie-quality-config/spotbugs/spotbugs-exclude.xml` is a **per-type
list** — SpotBugs' `Field/@type` matcher takes no regex, so a generalised pattern silently matches
nothing. Add the type. Do not add a defensive copy: copying a repository proxy is meaningless.

---

## Tests

### `Could not find a valid Docker environment`

Testcontainers needs a working Docker socket. Docker Desktop is not running, or WSL integration is
off for this distro. Every `@SpringBootTest` that uses a real database fails at context load.

### Vitest reports worker timeouts while every test passes

Environmental slowness on `/mnt/d` (a Windows drive mounted into WSL); the setup phase alone can
take ninety seconds. The tests are fine. Moving the working copy onto the Linux filesystem removes
it, at the cost of Windows tooling reaching the files.

---

## Local end-to-end runs

### No OTP email arrives

Two causes, in order of likelihood:

1. **The OTP was never requested.** Creating a registration does not send one — `POST
   /api/v1/registrations/{id}/email-otp` does, and answers `202`, because issuing it is
   asynchronous.
2. **You looked too early.** The mail travels through the outbox relay, the broker and comms, on the
   relay's schedule. Poll the mail catcher; do not sleep a fixed guess.

Check the chain in order: `outbox_events` (is the event there and published?), the broker queue, then
comms' log for `Sending registration email OTP`.

### Everything 429s partway through a test run

The rate limits are real and per-minute. `/api/v1/registrations/**` is capped at 5/min and the
credential route at 5/min. For a load or registration run, raise the budget for that run —
`IPIE_RATE_LIMIT_REGISTRATIONS_LIMIT=<threads x loops x 3>` — and never disable it. Between runs,
wait the window out.

### Events are published but nothing consumes them

`SPRING_RABBITMQ_HOST` unset makes event publishing degrade silently to a logging fallback: the
outbox fills, nothing is delivered, and no error appears. Check the variable before suspecting the
consumer.

### A service starts on the wrong port, or collides with Keycloak

Every service defaults to `SERVER_PORT=8080`, which is Keycloak's. The compose file sets the real
ports; a service started by hand needs it explicitly.

---

## Configuration

### A setting in a YAML file is ignored

Look for a duplicate top-level key. In plain YAML the last one wins, so appending a second `ipie:`
block silently discards everything under the first — this dropped `ipie.security.enabled` and the S3
encryption settings from a production profile. Under a stricter loader the profile refuses to start
instead, which is the better outcome.

### A deployed service runs with development defaults

`SPRING_PROFILES_ACTIVE` was set to the environment name alone. The shared hardening lives in
`application-deployed.yml`, and Spring applies profile documents in the order listed, so it must be
`deployed,<env>` — environment second, so it wins. Setting only `prod` starts the service on
`application.yml`'s development defaults: full trace sampling, no hardening. It looks like success.

### The dev server serves stale code after an edit or a branch switch

Two different causes, and the second is the one that wastes an afternoon.

1. **A leftover server from an earlier session** is still bound to 5173 and serving its own bundle
   *(DevEnv §26.3.7)*. `ss -ltnp | grep 5173` or `ps aux | grep vite` before assuming a real bug.

2. **File watching does not work on `/mnt/d`.** A working copy on a Windows drive gets no inotify
   events in WSL, so Vite never learns that a file changed and keeps serving the module graph it
   transformed at startup. Nothing looks wrong: no error, no warning, and the page reloads happily
   with the old code. It is most confusing straight after a branch switch, when many files change at
   once and none of them takes effect.

   Confirm it in one command - ask the server what it is serving and compare with the file:

   ```bash
   curl -s http://127.0.0.1:5173/src/components/AppLayout.tsx | grep "label:"
   grep "label:" src/components/AppLayout.tsx
   ```

   If they differ, the server is stale: restart it. Refreshing the browser cannot help, because the
   stale copy is on the server side. Note that files under `public/` are read per request and *do*
   update, which makes the situation look inconsistent - part of the application picks up changes
   and part does not.

   Moving the working copy onto the Linux filesystem removes the problem, at the cost of Windows
   tooling reaching the files.

### The frontend points at localhost after deployment

`config.json` was not replaced. The bundle is environment-agnostic by design and reads its endpoints
from that file at startup; the copy inside the image holds local values so the image runs on its own.
Whatever serves the static files must mount the environment's own copy over it.
