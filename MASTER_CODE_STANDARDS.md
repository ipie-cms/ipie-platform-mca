# iPIE Master Code — Backend Development Standards

| Document Item | Value |
|---|---|
| Project | iPIE Platform |
| Document Type | Master Development Standards — Backend (Gradle Groovy) |
| Scope | Backend only - `ipie-web` (the real, built frontend) is covered by `Frontend_Environment_Configuration.md` instead. |
| Status | Verified against the working tree |

This document describes the master code committed in this repository: architecture, build system,
layering rules, and — in **Section 12** — everything deliberately left open or simplified, so it
is decided deliberately rather than discovered by accident later.

**Per-class rationale — why an individual class exists — lives in `SERVICE_CLASS_REFERENCE.md`,
not here.** This document links to it wherever a class is mentioned, rather than re-explaining
each one; keep new classes documented there, not duplicated back into this file.
**`SERVICE_CLASS_REFERENCE.md` is not verified against the package layout below — its per-class
file paths still describe an older `api/application/domain/infrastructure` layout and need the same
correction.**

**What "verified against the working tree" means, precisely.** Everything about code structure —
package layout, class names, endpoints, config precedence, migration files, ArchUnit rules, module
inventory, test counts — is checked directly against the working tree, including running
`ipie-user-service`'s `UserControllerIntegrationTest` for real (Testcontainers PostgreSQL, a row
inserted and an outbox event published) to confirm the mapper chain described in Section 5 behaves
as documented. That test needs a Docker daemon: without one it does not run, and a green build then
says the code compiles, not that a service starts.

**The live-infrastructure narratives below are not continuously re-driven** — the full
`docker compose up` stack, real Keycloak login flows, the Kafka↔RabbitMQ broker switch,
MinIO/ClamAV upload verification, Jaeger/Prometheus/Grafana dashboards. They are marked explicitly
where they appear and should be re-confirmed live before being relied on: nothing in the codebase
suggests they have broken, but a green build does not exercise them.

---

## 1. Main Rule (unchanged)

No iPIE microservice should be created from scratch by an individual developer. New services
start from `ipie-service-template`, apply the approved Gradle convention plugins, and consume
the `ipie-common-libs:*` modules as **library dependencies** — never as separately deployed
services. This repository is the reference implementation of that rule: `ipie-service-template`
is a real, running service (not a skeleton) so the pattern is visible end to end, not just
described.

---

## 2. What the Platform Provides — Shared Modules

| Module | Path | Purpose |
|---|---|---|
| Root build | `settings.gradle`, `build.gradle`, `gradle.properties` | Multi-project Gradle Groovy build wiring every module below together. |
| Convention plugins | `ipie-build-conventions/src/main/groovy/ipie.*.gradle` | The *only* place Java version, static analysis and test wiring are configured — see Section 4. |
| `ipie-parent` | `ipie-parent/build.gradle` | Java Platform (BOM) fixing Spring Boot 3.5.16, MapStruct, ArchUnit, Testcontainers, springdoc, logstash-encoder and Resilience4j versions for every module. |
| `ipie-quality-config` | `ipie-quality-config/**` | Checkstyle rules (main + relaxed test variant), SpotBugs exclude filter, a Sonar properties template. |
| `ipie-common-libs` | `ipie-common-libs/**` | Every shared-library concern as one Gradle module, organized by package (see table below) — a service takes exactly one `implementation project(':ipie-common-libs')` dependency instead of one per concern. It is one module rather than 13; the rationale for the merge (and what's deliberately *not* folded in as a plain package — `testing`, kept as a `testFixtures` source set, not `src/main`) is in `ipie-common-libs/README.md`. |
| `ipie-service-template` | own repository | **Clone this to start a new service.** | The approved service skeleton — a fully working User CRUD service against PostgreSQL, and a Document upload vertical slice (Section 14) demonstrating the file-upload rules end to end. RabbitMQ is the active broker by default (Kafka wiring stays behind the same config switch, Section 11). See Section 5. |
| Local dev stack | `docker-compose.yml`, `deploy/keycloak/realm-export.json` | PostgreSQL, RabbitMQ (**active broker by default** — Kafka is commented out, not removed, see Section 9), Keycloak (realm pre-imported, host port `8080`), Redis (backs `cache`/`session`), Jaeger (tracing UI `16686`), Prometheus (`9090`)/Grafana (`3000`) (dashboards provisioned), Elasticsearch (`9200`, search read path), the ELK log stack — Logstash (`5044`), a **second** Elasticsearch `elasticsearch-logs` (`9201`, logs only, deliberately not the `9200` one) and Kibana (`5601`, log search UI) — MinIO (`9000`/`9001`, S3-compatible file storage) + ClamAV (`3310`, virus scanning), MailHog (`8025`, email capture for local dev), OPA (`8181`, **provisioned but not yet called from any service code** — see Section 12), pgAdmin (`5050`). **No service is built from this repository.** This is the infrastructure stack only; each service runs from its own repository against it, on the ports it always used — `ipie-user-service` `8092`, `ipie-iam-service` `8093`, `ipie-communication-service` `8094`. A full registration flow spans all three, so end-to-end testing needs them running alongside this stack. |

The deployable services built on these modules — `ipie-user-service`, `ipie-iam-service`,
`ipie-communication-service`, the planned `ipie-audit-service`, and the `ipie-keycloak-spi` module
that runs inside Keycloak — are described in **`ARCHITECTURE_WORKING_PLAN.md` §4.0**, together with their
API endpoints (§4.0.1). They are deliberately not listed here: this document is what a *new*
service is built against, and it should not need revising every time one is added.

### Commit and branch standards

**No tool or AI attribution in a commit message, ever.** No `Co-Authored-By` trailer naming an
assistant, no "generated with" line, no reference to the tooling used to write the change. These
repositories are a government-sector deliverable and the commit history is part of what is handed
over; it records what changed and why, not what typed it.

| | Convention |
|---|---|
| Branch | `IPIE-NNN-short-description`, e.g. `IPIE-002-credential-authority`. Branch before committing when sitting on `master`. |
| Subject | `IPIE-NNN: Imperative subject`, e.g. `IPIE-002: Become the credential authority`. |
| Body | **Why the change is shaped the way it is, and what it prevents.** The diff already shows what changed; the body carries the reasoning that would otherwise be lost — the constraint that forced the design, the failure mode avoided, the thing that looked correct and was not. |
| Scope | One commit per repository per change. A change spanning services gets a commit in each, sharing the `IPIE-NNN` prefix, so the set can be found later. |

Push only when asked. A commit is not a push.

**Documentation format discipline** (the rule the `.gitignore` exists to support - it tracks the
`.md` and ignores every `.docx`): `.md` is the source, the thing reviewed in a diff, and the only
format git carries. `.docx` is the readable copy, **generated from it**
(`pandoc X.md -o X.docx --toc`), kept on disk and handed over directly rather than committed.
Editing the `.docx` directly lets the two drift, which is exactly how the previous copy fell three
versions behind. A `.docx` is also a zip, so its text is compressed and no grep over the repository
can see inside one - the second reason none of them is version controlled.

### Local run topology

**The three services run as Docker containers. Keycloak and PostgreSQL run on the host.**

| Component | Where | How |
|---|---|---|
| **Keycloak** | **host** | `D:\keycloak-26.6.3`, started per the procedure below, port `8080` |
| **PostgreSQL** | **host** | port `5432` |
| RabbitMQ, Redis, Elasticsearch, MinIO, ClamAV, MailHog, Jaeger, Prometheus, Grafana, OPA | Docker | `docker-compose.yml` — infrastructure only, builds nothing |

This is not a preference, it is what makes the Keycloak SPI work. The `ipie-keycloak-spi` authenticators call `ipie-iam-service` *from inside Keycloak's own process*, which runs on the host. Docker publishes a container's port onto the host, so `localhost:8093` resolves normally. A service started instead as a plain host/WSL JVM has **no published port**, and Keycloak cannot reach it — not on `localhost`, not on the WSL IP. Every SPI→iam call then fails and the authenticator fails closed with `503 temporarily_unavailable`, which looks exactly like an application bug and is not one.

**Therefore: do not use `start-ipie.sh`'s host-JVM mode when testing anything that touches the SPI** — login, credential verification, or pillar SSO resolution. That script is the *other* run mode and competes for the same ports; the two cannot both be up.

Containers reach the host through `extra_hosts`: `host.docker.internal:host-gateway` for PostgreSQL and `keycloak:host-gateway` for Keycloak. **The alias must be exactly `keycloak`** — Keycloak stamps `http://keycloak:8080` into every token's `iss` claim and every service validates that exact string, so `host.docker.internal` will not do. A service run outside a container needs the same name resolvable (`127.0.0.1 keycloak` in `/etc/hosts`).


#### Starting Keycloak — the two steps that are not optional

```powershell
cd D:\keycloak-26.6.3
# 1. load the SPI settings into the session Keycloak will inherit
Get-Content "<repo>\ipie-platform-mca\deploy\keycloak\env\spi.dev.env" |
  Where-Object { $_ -and $_ -notmatch '^\s*#' } |
  ForEach-Object { $n,$v = $_ -split '=',2; Set-Item "Env:$n" $v }
# 2. bind on all interfaces, not just localhost
bin\kc.bat start-dev --http-host=0.0.0.0
```

`deploy/keycloak/start-keycloak.ps1` does both, refuses to start a second instance over the first,
and captures the console to a log file.

**Loading the environment file is what makes a login possible at all.** The credential SPI signs its
call to ipie-iam-service with a shared key and has no usable default for it - an empty key must fail
closed, because the alternative is a login succeeding unverified. Start Keycloak without those
settings and the token endpoint answers `unknown_error` while iam records no request whatsoever,
which reads as a platform fault and is nothing of the kind. Costs a diagnosis cycle every time, because nothing about the symptom names the cause.

**`--http-host=0.0.0.0` matters on Windows only.** Dev mode otherwise binds `localhost`, and WSL's
`localhost` is a different machine, so no WSL shell or script can reach Keycloak - every token call
then has to be driven from inside a container instead. The SPI itself is unaffected either way; it
runs inside Keycloak's own process.

### When something breaks — `ipie-platform-mca/TROUBLESHOOTING.md`

Every failure that has cost real time on this platform, by symptom, with the cause and the fix that
worked: logins failing closed, Docker publishing no ports to Windows, Flyway refusing a shared
schema, a quality-config change that appears to do nothing, an OTP that never arrives. Nearly all of
them look like application bugs and are not, which is exactly why they are written down.

**Add to it when you solve something that is not there.** The entry costs ten minutes and saves the
next person a day; a fix that lives only in one developer's memory gets rediscovered at full price.

### Service responsibilities — see the architecture plan

**This document deliberately does not describe the services.** It governs how to build *a* service:
layering, build, security baseline, database, observability, quality gates. What each microservice
owns, what it must never hold, how the services talk to each other, and their API endpoints live in
**`ARCHITECTURE_WORKING_PLAN.md` §4.0 / §4.0.1**, which is the single canonical source for that.

The rule for which document a change belongs in:

| The change is about... | Document |
|---|---|
| A standard every service must follow — layering, naming, migrations, auth wiring, testing, logging | **this document** |
| What a specific service owns, its endpoints, its events, a new service being added | **`ARCHITECTURE_WORKING_PLAN.md`** |

Two boundary rules stated here because they are *standards* a new service must apply, not facts
about an existing one:

- **The ownership test for anything new.** Could this exist and still be meaningful *before* the
  person has an account? Then it is business data and belongs to the domain service. Is it
  meaningless without an account? Then it belongs to `ipie-iam-service`. A registration draft, an
  address and an identity proof pass the first test; a password, a role grant and a lockout counter
  fail it.
- **Recipient addresses are configuration, not code.** `findEmailByPurpose` resolves an address from
  the database, so reading the sending code tells you nothing about who receives a message. Check
  the purpose's configured recipient before designing a flow or writing documentation around it —
  assuming otherwise is what produces a dead-ended registration flow.

### `ipie-common-libs` packages

| Package | Purpose |
|---|---|
| `core` | `IpieException`/`NotFoundException`/`ConflictException`/`ValidationFailedException`, `AuditMetadata` (now 8 fields: the original `createdAt/createdBy/updatedAt/updatedBy/version` plus `isActive/deletedAt/deletedBy` for the platform-wide soft-delete standard), framework-agnostic `PageRequest`/`PageResult`, correlation-id constants. Zero framework dependencies on purpose - the base of the dependency graph every other package may depend on, never the reverse (enforced by `CommonLibsDependencyDirectionTest`, an ArchUnit test in the module's own `src/test`). |
| `persistence` | `AuditableJpaEntity` - a `@MappedSuperclass` every service's JPA entities extend for the standard audit + soft-delete columns (`created_at/created_by/updated_at/updated_by/version/is_active/deleted_at/deleted_by`), so no entity declares them by hand. JPA itself (`jakarta.persistence-api`, `spring-data-commons`) is `compileOnly` here, same precedent as Redis in `cache`/`session` - a service without JPA on its classpath still compiles fine. `IntegrityViolations` + `IdCollisionException` translate a `DataIntegrityViolationException` into the error the failing constraint actually means - each repository declares the constraint names of the table it owns and publishes them as a bean, since the same violation can surface either inside the repository call or from the transaction commit (Section 13.1). |
| `web` | `ApiError` (the one response shape), `GlobalExceptionHandler`, `PageResponse`. |
| `security` | JWT resource-server auto-configuration, `CurrentUserProvider`/`CurrentUser`, `PermissionEnforcer` (permission checks, not role checks). |
| `observability` | `CorrelationIdFilter`, `LoggingContext`, the shared JSON logback appender fragment. |
| `audit` | `AuditEvent`/`AuditEventType`, `AuditRecorder` port + `OutboxAuditRecorder` (durable, active in `ipie-service-template` today via the transactional outbox) with `LoggingAuditRecorder` as the fallback for a service with no `OutboxStore` bean, `@Auditable` + `AuditAspect` (SpEL-based). |
| `events` | `EventEnvelope`, `EventPublisher` port, `OutboxStore`/`OutboxRelay` for the transactional outbox pattern, `ProcessedEventStore` + `IdempotentEventHandler` for consumer-side idempotency. The port is broker-agnostic on purpose - `ipie-service-template` supplies concrete Kafka and RabbitMQ bindings, selected by which is configured (Section 9). |
| `testing` (`testFixtures`) | `PostgresIntegrationTest` and `ElasticsearchIntegrationTest` (Testcontainers mixins - the Elasticsearch one pins `ES_JAVA_OPTS` to a 512m heap, because a container with no memory limit sizes its heap from the whole host and is OOMKilled before it reports ready, which looks like a slow start rather than a memory ceiling), `LayeredArchitectureRules` (reusable ArchUnit rules). Lives under `src/testFixtures`, not `src/main` - consumed via `testImplementation testFixtures(project(':ipie-common-libs'))`, so Testcontainers/`spring-boot-starter-test` never land on a service's main/runtime classpath. |
| `utils` | `DateTimeUtils`, `Strings`, `DataMasking` (PAN/Aadhaar/email/generic masking), `IdGenerator`, plus `JsonUtils`/`ValidationUtils`/`CollectionUtils`/`NetworkUtils`/`ExceptionUtils`. Dependency-light on purpose — usable from batch jobs/CLI tooling too. |
| `resilience` | Resilience4j-spring-boot3 wiring: shared "default" Retry (exponential backoff + jitter, allowlisted-exception-only)/CircuitBreaker/Bulkhead/TimeLimiter config via `ipie-resilience-defaults.yml`, plus a `RestClientCustomizer` for connection/response timeouts. A service only needs the dependency and an `@Retry`/`@CircuitBreaker`/`@Bulkhead`/`@TimeLimiter(name = "...")` annotation - no per-service `resilience4j.*` YAML for the common case. See `Development_Environment_Configuration.md`, Section 15, Resilience. |
| `client` | Generic, secured `InterServiceClient` for outbound calls between microservices - one `exchange`/`execute` pair covers every HTTP method/body shape via `ServiceRequest`, resolved to a target service's base URL (`ipie.client.*`), decorated per-target-service with `resilience`'s Retry/CircuitBreaker/Bulkhead defaults, and secured via Keycloak OAuth2 client-credentials by default (`ipie.client.security.mode`, token-relay opt-in), with opt-in HMAC request signing, OPA authorization and mutual TLS (`ipie.client.security.mtls.*`) layered on top - see `Development_Environment_Configuration.md`, Section 15, Resilience. |
| `filestorage` | `FileStorage`/`VirusScanner` ports, `FileTypeValidator` (Tika content-sniffing)/`FileSizeValidator`, `StorageKeyGenerator`, `FileHasher`. Broker-agnostic in the same sense as `events` - `ipie-service-template` supplies the concrete S3/ClamAV bindings, selected by config. See Section 14. |
| `cache` | `IpieCacheAutoConfiguration` - auto-configures Spring's cache abstraction (`@Cacheable`/`@CacheEvict`/`@CachePut`) with zero per-service YAML: a Redis-backed `CacheManager` when a service also adds `spring-boot-starter-data-redis` (opt-in per service, `compileOnly` in `ipie-common-libs`) and `spring.data.redis.host` is configured, a no-op `CacheManager` otherwise. See "Switching the cache backend" under Section 11. |
| `session` | Common idle-session timeout, independent of the JWT's own `exp` - on by default, auto-registers `SessionActivityFilter` plus the `/api/v1/session/{status,extend,logout}` REST surface backing `ipie-web`'s "stay logged in?" prompt. `SessionStore` is Redis-backed (TTL as the expiry mechanism) when `spring.data.redis.host` is configured, in-memory single-instance fallback otherwise - same opt-in-Redis precedence as `cache`. See Section 15. |

To be precise about what "frontend" means here: `ipie-web` (a real, fully built React 19 + RTK
Query app - login, Users screen, session-timeout handling, 8 test files) exists and works; see
`Frontend_Environment_Configuration.md` for it in full. What doesn't exist yet is a reusable
**`ipie-frontend-template`** scaffold module - the frontend counterpart to `ipie-service-template`
- that a *new* frontend could be copied from the way a new backend microservice copies
`ipie-service-template` today. `ipie-web` itself was built as a one-off app, not as that
reusable scaffold. Listed in Section 12 as a follow-up.

This table maps Gradle modules to what they contain, at a glance — for why any individual class
in a module exists, see `SERVICE_CLASS_REFERENCE.md`.

---

## 3. Repository Structure

```
ipie-platform-mca/                     (rootProject.name = 'ipie-platform-mca')
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradlew, gradlew.bat, gradle/wrapper/         (real, working Gradle 8.10.2 wrapper)
├── ipie-build-conventions/                       (convention plugins, published - see Section 4)
├── smoke-test/                                   (proves the published plugin enforces the rules)
├── .github/workflows/                            (CI: quality gate; publish on a version tag)
├── docker-compose.yml
├── .dockerignore
├── ipie-parent/                                  (version BOM)
├── ipie-quality-config/
│   ├── checkstyle/{checkstyle.xml, checkstyle-test.xml}
│   ├── spotbugs/spotbugs-exclude.xml
│   └── sonar/sonar-project.properties
├── ipie-common-libs/                             (single module, organized by package - core/
│   │                                               web/security/observability/audit/events/
│   │                                               resilience/client/utils/filestorage/cache/
│   │                                               session, plus testFixtures for `testing`)
└── extract-service.sh                            (extracts a module into its own repository)

Every service lives in its OWN repository and builds against this platform's
published artifacts. This repository ships artifacts; service repositories ship containers.

github.com/ipie-cms/
├── ipie-service-template/     (clone this to start a new service)
├── ipie-user-service/
├── ipie-iam-service/          (plus keycloak-spi/ as a subproject - see below)
├── ipie-communication-service/
└── ipie-web/                  (React frontend, its own repository)

A service repository is flat: the former module directory becomes the repository root.

<service-repo>/
├── settings.gradle           (resolves ipie.* plugins from the published ipie-build-conventions)
├── build.gradle              (platform deps as coordinates, never project(':...'))
├── gradle.properties         (ipiePlatformVersion pins the platform)
├── lombok.config             (NOT optional - see below)
├── MASTER_CODE_STANDARDS.md  (this document travels with every service)
├── Dockerfile
└── src/{main,test}/java/in/gov/ipie/service/<name>/

`ipie-keycloak-spi` lives in the `ipie-iam-service` repository, not here: the two implement
pillar SSO federation together across the `/internal/pillar-links/resolve` contract,
which changes on both sides at once. It is not a service - it deploys as a jar into Keycloak's
providers directory.

**`lombok.config` is not optional in a service repository.** It carries
`lombok.copyableAnnotations += ...Value`. In the monorepo it sat at the root and every module
inherited it silently; an extracted repository inherits nothing. Without it Lombok drops
`@Value("${...}")` from the constructor `@RequiredArgsConstructor` generates and the service fails
to start with `NoSuchBeanDefinitionException`. Three of the four extractions passed without it -
only `ipie-user-service` has a class that exercises it - so its absence is silent until it isn't.
        ├── controller/            HTTP only: controllers
        ├── dto/{request,response}/   Request/response DTOs
        ├── mapper/                MapStruct DTO <-> domain mapper
        ├── command/                Use-case input records (*Command)
        ├── domain/                 Business rules: model + status/sort enums (
        │                           see note below the table)
        ├── repository/             Domain-owned port interfaces (*Repository, *SearchIndex)
        ├── exception/               Domain errors
        ├── event/                   Event-type enums (*EventType)
        ├── service/                 Use-case orchestration (@Transactional here) + *ServiceImpl
        ├── persistence/            JPA entities/repositories/mappers/specifications - one flat
        │                           package per service (not split into entity/repository/
        │                           repositoryimpl/mapper/specification subpackages - see note)
        ├── search/                 Elasticsearch document/repository/searchindex/mapper (flat)
        ├── messaging/              Kafka+RabbitMQ publishers/consumers, outbox (flat)
        ├── permission/              *Permissions classes (PermissionEnforcer call sites)
        ├── scanning/                VirusScanner port impls (Document slice only)
        ├── storage/                 FileStorage port impls (Document slice only)
        ├── configuration/           Java-based configuration
        ├── integration/             Outbound HTTP clients to other services / external IdPs
        │                           (ipie-user-service only today)
        └── examples/                common-libs usage reference snippets, not production code
```

**No `idempotency/` package.** A per-service one breaches the "Do NOT" row in Section 13.1
("Enforced by a single shared mechanism, not a per-service copy"), and it is a live defect rather
than an untidiness: a local `IdempotencyAspect` declares the same pointcut as
`common-web`'s (`@Around("@annotation(...web.idempotency.Idempotent)")`), and
`IdempotencyAutoConfiguration` registers that one unconditionally, so `@Idempotent` is advised
twice by two aspects backed by two different stores, with no `@Order` between them — and the
template is what every new service is copied from. No service carries the package or an
`idempotency_keys` table. `@Idempotent` works everywhere —
`common-web`'s aspect handles it, proven by the template's own
`createUser_withRepeatedIdempotencyKey_doesNotCreateTwice`. `idempotency` remains in
`LayeredArchitectureRules.INFRASTRUCTURE_PACKAGES`, harmlessly: it constrains a package no service
has.

**This is a flat, single-level package layout per service — not an
`api/application/domain/infrastructure` wrapper-folder structure.** Every subpackage such a
structure would nest (`api.controller` →
`controller`, `application.service` → `service`, `domain.model` → `domain`, `infrastructure.
persistence` → `persistence`, etc.) is now its own top-level package directly under the service's
base package, and `domain/model` was additionally renamed `record/` → `domain/` that same day (a
plain-language package name was preferred; see `ipie-common-libs/src/testFixtures/java/in/gov/
ipie/common/testing/archunit/LayeredArchitectureRules.java`'s class Javadoc for the rationale).
`persistence/`, `search/`, and `messaging/` also stayed **flat, not split into artifact-type
subpackages** (`entity/`, `repository/`, `mapper/`, ...); Section 5 below describes what is in
`persistence/`. Section 5 has the full
layering rationale and the concrete `User` vertical-slice file-path table, rewritten to match.

`ipie-quality-config` is intentionally **not** a Gradle subproject — it holds only config files
referenced by `ipie-build-conventions`'s convention plugins. Inside this repository they read it
from this folder directly; `ipie-build-conventions` also packages a copy into its published jar, so
a service building outside this repository extracts the identical rules rather than inventing its
own (see `smoke-test/`, which is what proves that path works).

---

## 4. Build System — How Versions and Tooling Are Actually Governed

This is Gradle **Groovy** DSL throughout. Since Groovy doesn't have Maven's "parent POM" concept,
the same governance is achieved two ways:

1. **`ipie-parent`** is a `java-platform` module. Every other module does
   `implementation platform(project(':ipie-parent'))` (or `api platform(...)` for libraries),
   which is Gradle's *native*, correctly-scoped BOM mechanism — it re-exports the Spring Boot
   BOM plus a handful of extra pinned versions (MapStruct, ArchUnit, springdoc, Testcontainers,
   logstash-encoder). **No module declares its own version for anything this platform manages.**

   Every pinned version is the latest stable release **compatible with Spring Boot 3.5.x and
   Java 21** specifically (checked against Maven Central at the time of writing, not assumed):

   | Component | Version | Note |
   |---|---|---|
   | Gradle | 9.6.1 | Verified against this exact project - no changes needed |
   | Spring Boot | 3.5.16 | Latest 3.5.x patch. **Not** 4.1.0 - that's a new major (Spring Framework 7) requiring its own migration; springdoc 3.x and Testcontainers... (see below) track *that* line, not this one |
   | Checkstyle | 13.7.0 | 13.0.0 raised its own minimum to JDK 21 - exactly matches this project's baseline |
   | Testcontainers | 2.0.5 | See breaking-change note below |
   | logstash-logback-encoder | 9.0 | Depends on logback-classic 1.5.20; Spring Boot 3.5.16 manages 1.5.34, which satisfies it |
   | springdoc-openapi | 2.8.17 | Latest in the *2.x* line - 3.x's POM references Spring Boot-4-only artifact names (`spring-boot-tomcat`, `spring-boot-health`), i.e. it requires Boot 4 |
   | MapStruct / ArchUnit | 1.6.3 / 1.4.2 | Already latest at initial build time - unchanged |
   | Resilience4j | 2.4.0 | Latest stable; `resilience4j-spring-boot3` targets Spring Boot 3.x generally, not pinned to a specific minor |

   **Testcontainers 2.0 breaking changes that affected this repo:** module artifacts gained a
   `testcontainers-` prefix (`org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`,
   same for `junit-jupiter`), and `PostgreSQLContainer` dropped its self-referencing generic type
   parameter (`PostgreSQLContainer<?>` → plain `PostgreSQLContainer`). Both are fixed in
   `common-testing`'s `build.gradle` and `PostgresIntegrationTest.java`. The `testcontainers-bom`
   coordinate itself is unchanged.
2. **`ipie-build-conventions/src/main/groovy/ipie.*.gradle`** are precompiled Groovy script plugins
   (the Groovy-DSL equivalent of Kotlin's convention-plugin pattern). It is deliberately not
   `buildSrc/`: Gradle treats that directory specially and makes it visible only to its own build,
   so a service outside this repository could not apply these conventions at all. As an included
   build it is an ordinary project that also publishes. Four are defined:
   - `ipie.java-conventions.gradle` — Java 21 toolchain, Checkstyle, SpotBugs, JaCoCo, JUnit 5
     wiring. Applied by the other two, never applied directly by a module.
   - `ipie.library-conventions.gradle` — for everything under `ipie-common-libs`.
   - `ipie.spring-service-conventions.gradle` — for deployable services (applies the Spring Boot
     Gradle plugin, wires `bootJar`, common test dependencies).

   A service's `build.gradle` is typically just:
   ```groovy
   plugins {
       id 'ipie.spring-service-conventions'
   }
   dependencies {
       implementation project(':ipie-common-libs')
       // service-specific deps only
   }
   ```
   `ipie-common-libs` itself (the one module `ipie.library-conventions` applies to — see Section 2) declares every shared-library dependency once, in its own
   `build.gradle`.

### A deliberate omission: no `io.spring.dependency-management`

Standard Spring Boot Gradle setups apply `io.spring.dependency-management` alongside the Spring
Boot plugin. **This repo does not.** That plugin rewrites dependency versions across **every**
configuration in a project — including SpotBugs' own analysis classpath — with no supported way
to scope it down; it downgraded SpotBugs' own `commons-lang3` dependency below what its bundled
BCEL requires and crashed the analysis with `NoClassDefFoundError`. Since `ipie-parent`'s
`platform(...)` import already provides the exact same BOM through Gradle's native,
correctly-scoped mechanism, the plugin added nothing and was removed. **If you ever re-add it,
re-check that SpotBugs still runs.**

### A second gotcha worth knowing before you add a new common-lib package with its own bean

A plain `@RestControllerAdvice`/`@Component` in a shared library is **not** picked up by a
service's component scan — `@SpringBootApplication`'s scan is rooted at the application class's
own package (`in.gov.ipie.service.template`), which never includes `in.gov.ipie.common.*`. Every
common-lib bean that must be active in every service is registered through Spring Boot's
auto-configuration mechanism instead — one shared file listing every auto-configuration class
from every package:
```
ipie-common-libs/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```
`security`, `observability` and `audit` all had this from the start (back when each was its own
module with its own copy of this file). **`web`'s `GlobalExceptionHandler` initially did not** —
it silently never fired, so every error came back as Spring's default
`{"timestamp","status","error","path"}` body instead of the common `ApiError` shape, and a 409
conflict came back as a raw 500. This was only caught by manually POSTing a duplicate username
against a real database, not by any static check — which is why Section 11 recommends you don't
skip that kind of verification either.
**Anyone adding a new common-lib `@Component`/`@Bean` that must apply to every service must add
it to this shared `AutoConfiguration.imports`, not rely on component scanning.**

### A third gotcha: SpotBugs' exclude filter `Field/@type` does not support regex

`ipie-quality-config/spotbugs/spotbugs-exclude.xml` suppresses `EI_EXPOSE_REP2` for
constructor-injected framework collaborators (`ObjectMapper`, `KafkaTemplate`, `RabbitTemplate`,
`IpieSecurityProperties`, and `UserService` itself, wherever a controller/service holds one as a
field) that are legitimately shared, mutable singletons. Unlike SpotBugs' `Class/@name` matcher,
`Field/@type` compares an exact JVM type - a `~(a|b|c)`-style regex there silently matches
nothing, so the exclusion appears to work (no XML error) but the finding comes right back. Each
collaborator type needs its own `<Match>` entry - and because SpotBugs only re-analyzes a module
when something in it actually changes, a stale exclusion gap like this can sit unnoticed for a
long time until an unrelated change in the same module forces a fresh pass and it resurfaces.

---

## 5. Backend Layering Standard

**This section describes a flat, single-level package layout.** Every service was restructured
out of any `api/application/domain/infrastructure` wrapper folders —
see Section 3's note for the mechanics of that change:

```
controller/, dto/, mapper/                 API side: HTTP only, request/response DTOs, MapStruct DTO mapper
service/, command/, domain/, repository/, exception/, event/
                                            Business side: use-case orchestration (@Transactional
                                            in *ServiceImpl), model + status/sort enums, domain-owned
                                            port interfaces, domain errors, event-type enums
persistence/, search/, messaging/, storage/, scanning/, configuration/, integration/
                                            Infrastructure: JPA entities/repositories/mappers/
                                            specifications, Elasticsearch adapters, Kafka+RabbitMQ +
                                            outbox, S3, ClamAV, Java config, outbound HTTP clients
```

`integration/` is listed here because `LayeredArchitectureRules.INFRASTRUCTURE_PACKAGES` has always
included it — earlier versions of this section omitted it while the rule enforced it, so
`ipie-user-service`'s `integration/` package was constrained by a boundary this document did not
mention. `idempotency/` is absent: no service has one (see Section 3).

Enforced automatically, not just documented — every service's own `ArchitectureTest`
(`src/test/java/.../architecture/ArchitectureTest.java`) applies `LayeredArchitectureRules` from
`ipie-common-libs`'s `testFixtures` (`in.gov.ipie.common.testing.archunit`). The rules operate on
sets of top-level packages, not one `..layer..` glob each, since there's no wrapper folder left to
glob on:
- `controllersDoNotAccessRepositoriesDirectly` — `controller` must not depend on `repository`
- `domainDoesNotDependOnInfrastructure` — `domain` must not depend on any of `persistence`,
  `messaging`, `storage`, `scanning`, `search`, `configuration`, `idempotency`, `integration`
- `applicationDoesNotDependOnInfrastructure` — the same, widened to `service`/`command`/`query`
  as well as `domain`. **This is the rule that matters; the narrower one is a special case of it.**
  `APPLICATION_PACKAGES` lists `service` and `INFRASTRUCTURE_PACKAGES` lists `persistence`, so
  guarding only `..domain..` leaves `..service.. → ..persistence..` checked by nothing — and that
  gap is invisible, because the build stays green while the leaks accumulate. The shapes it
  catches: a service class holding Spring Data repositories and a JPA entity directly, or holding
  a concrete HTTP client instead of a port.
- `applicationDoesNotDependOnPlatformAdapters` — application packages must not depend on
  `in.gov.ipie.common.security.keycloak.admin`, the platform's own adapter package. It is needed
  because the rules above are scoped to a service's own base package and so cannot see
  `KeycloakUserManagementClient`, which `RoleServiceImpl` and `AccountProvisioningServiceImpl`
  both injected directly. They now depend on `AccountDirectory` / `RealmRoleDirectory`
  (`in.gov.ipie.common.security.keycloak.directory`), two ports over the one adapter, split so a
  service that only provisions accounts does not compile against realm-role management it is not
  permitted to perform. Any future platform adapter with a port beside it belongs in
  `PLATFORM_ADAPTER_PACKAGES`.
- `applicationDoesNotDependOnApi` — `service`/`command`/`query`/`domain` must not depend on any of
  `controller`/`mapper`/`dto`
- `apiDoesNotExposePersistenceEntities` — `controller` must not depend on `persistence` (no
  leaking JPA entities through the API)
- plus four binding-rule checks from Section 13.2 (`exceptionsExtendIpieException`,
  `noCompetingControllerAdvice`, `applicationDoesNotCallEventPublisherDirectly`,
  `noHandWrittenPlatformPortAdapters`)

**A service binds all of them with one field, not ten.**
`LayeredArchitectureRules.all(BASE_PACKAGE)` returns the whole set as a single `ArchRule`, and
that is the only thing a service's `ArchitectureTest` declares:

```java
@AnalyzeClasses(packages = "in.gov.ipie.service.<name>")
class ArchitectureTest {
    private static final String BASE_PACKAGE = "in.gov.ipie.service.<name>";

    @ArchTest
    static final ArchRule ipie_layering_and_binding_rules = LayeredArchitectureRules.all(BASE_PACKAGE);
}
```

Binding each rule as its own `@ArchTest` field, copied into every repository, makes a platform rule
opt-in twice: a new rule reaches a service only when someone edits that service's copy, and reaches
a new service only if someone remembers to create the file at all. A service that has no
`ArchitectureTest` at all then passes every rule silently, while this document says otherwise.
With `all()`, a service picks up new rules on its next `ipiePlatformVersion` bump and no edit.
Suppressing one rule means replacing the field with the individual rules you do want, and saying
in a comment which you dropped and why: deliberately more work than adding one, and visible in
review.

Creating a new service means **cloning `github.com/ipie-cms/ipie-service-template`**, then
renaming: the repository, `rootProject.name` in `settings.gradle`, the base package
(`in.gov.ipie.service.template` → `in.gov.ipie.service.<name>`), and `ArchitectureTest`'s
`BASE_PACKAGE` constant. Nothing else about the layering changes.

`extract-service.sh` in this repository is the counterpart: it lifts a service that still lives
here into a repository of its own, which is how the existing services got theirs.

The new service's `gradle.properties` pins `ipiePlatformVersion`. Bumping it is deliberate - that is
the trade repo-per-service buys. Automate the bump as a pull request gated on the service's own CI;
never a dynamic `1.+`/`-SNAPSHOT` range, which would make builds irreproducible and let one bad
platform commit reach every service unchecked.

### Why ArchUnit — rich domain model vs. anemic POJO + service logic

Two ways to structure a service's business layer were considered. Both are legitimate, widely-used
patterns — the choice between them is what ArchUnit is actually enforcing, not a matter of one
being universally "correct."

| | Anemic model (entity = POJO, all logic in service) | Rich domain model (implemented here) |
|---|---|---|
| Entity/domain class | Getters/setters only — a data bag | Owns the methods that change its own state (e.g. `User.deactivate()`, `updateDetails()`) |
| Who can mutate state | Any code holding a reference, from any layer — a mapper, a test, a future developer | Only the object itself, through named methods that can enforce a rule before allowing the change |
| Where invariants are enforced | Wherever a developer remembers to check them — no single place | On the object whose state is being protected — one place to get it right, one place to look |
| Persistence coupling | Often the same class is both the domain model and the `@Entity` — business rules inherit Hibernate's annotations/lazy-loading behaviour | Domain object and JPA entity are two separate classes (`User`/`UserJpaEntity`); business logic never depends on persistence technology |

**Why ArchUnit specifically is what makes the rich-domain-model choice durable rather than
aspirational:** a design preference written only in a document is a convention — a developer can
violate it, and nothing catches it before code review, if it's caught at all. ArchUnit turns the
boundary the rich-domain-model choice depends on into a build-time check:
`domainDoesNotDependOnInfrastructure` fails the build the moment a domain class imports anything
from `persistence`/`messaging`/`storage`; `apiDoesNotExposePersistenceEntities` fails the build the
moment a controller depends on a JPA entity. Without these, the separation between `domain` and
`persistence` is only as strong as habit.

**One thing worth understanding, not just for compliance but for judgment:** ArchUnit's rules are
structural — they check *"does a class in package X depend on a class in package Y,"* not *"does
this class have business methods."* Nothing stops a developer from merging `domain` and
`persistence` into one `entity` folder of plain POJOs; ArchUnit has no rule against it. But doing
so would silently disable both checks above — `domainDoesNotDependOnInfrastructure` becomes
meaningless once there's no separate `domain` package to check *from*, and
`apiDoesNotExposePersistenceEntities` (which matches the literal package name `persistence`) would
no longer catch a controller depending on a renamed `entity` package, even though it's the same
underlying problem. ArchUnit protects the boundary as long as the boundary exists — it can't stop
someone from deleting the boundary itself.

### `repository/` (domain port) vs `persistence/` (JPA adapter) — same word "repository", different layers

Two different things use the word "repository" in this codebase — don't conflate them.
`repository/UserRepository.java` is the **port** (pure business vocabulary, no JPA/SQL knowledge);
`persistence/UserJpaRepository.java` is a **Spring Data JPA technology contract**, an
implementation detail of *how* persistence happens. `persistence/UserRepositoryImpl.java` is the
bridge, implementing the port on top of the technology contract. All three now live as siblings —
`UserRepository` at the top level, the other two inside the flat `persistence/` package — rather
than nested under a shared `domain`/`infrastructure` wrapper.

What matters architecturally is the same as before: `domain` must never depend on `persistence`
(the `domainDoesNotDependOnInfrastructure` ArchUnit rule above). If `UserServiceImpl` called
`UserJpaRepository` directly, swapping Postgres for something else, or unit-testing the service
without a real database, would be impossible without touching business logic. The port is the seam
that makes the swap (or a fake in tests) just a matter of providing a different `UserRepository`
implementation — see `UserServiceImplTest`, which does exactly this with a Mockito fake, no
database involved. The same port/adapter split applies to `UserSearchIndex` (domain port, in
`repository/`) vs. `UserDocumentRepository` (the Elasticsearch-specific Spring Data contract, in
`search/`).

### Class naming — the suffix is the contract

**A class's name ends in the suffix for what it is, and that suffix is the same in every service.**
The suffix is not decoration: it is how a reader knows which side of a layer boundary a file sits
on without opening it, and it is the only part of a class name a tool can reason about. Two
services must never use two suffixes for one concept.

| Suffix | Lives in | Is |
|---|---|---|
| `*JpaEntity` | `persistence/`, `messaging/` | A JPA `@Entity` or `@MappedSuperclass` row shape. **Never bare `*Entity`** — that reads as a domain type. |
| `*JpaRepository` | `persistence/` | A Spring Data interface. `*JpaRepositoryCustom` / `*JpaRepositoryCustomImpl` for a hand-written fragment. |
| `*Repository` | `repository/` | A **domain port** — business vocabulary only, no JPA, no `Specification`. |
| `*RepositoryImpl` | `persistence/` | The adapter implementing a port on top of a Spring Data interface. **Never `Jpa*Repository`** — a name beginning `Jpa` and ending `Repository` reads as a Spring Data contract and files itself with them. |
| `*PersistenceMapper` | `persistence/` | Entity ↔ domain. `*ApiMapper` in `mapper/` is DTO ↔ domain; the two never share a name. |
| `*Specifications` | `persistence/` | Static `Specification` factories built from a `*Criteria` record. |
| `*Converter` | `persistence/` | A JPA `AttributeConverter`. |
| `*SearchIndex` | `repository/` port, `search/` adapter | The search port and its Elasticsearch/JPA implementations. |
| `*Service` | `service/` | An **interface**. A concrete class must never end in `*Service`. |
| `*ServiceImpl` | `service/` | Its implementation. Every `*Service` has exactly one; a service class with no port is still `*ServiceImpl`. |
| `*Aspect`, `*Policy`, `*Resolver` | `service/` | Validation aspects, injectable decisions, scope resolution. |
| `*Controller` | `controller/` | An HTTP entry point. |
| `*Request`, `*Response` | `dto/request/`, `dto/response/` | The wire shapes. **A domain type must never end in `*Request`.** |
| `*Command` | `command/` | What crosses into the service layer. A request DTO never does. |
| `*Criteria` | `domain/` | List/search criteria as a record. |
| `*Consumer` | `messaging/` | A `@KafkaListener` / `@RabbitListener` class. **Never `*EventConsumer`** — the package already says messaging and the class name already says which event. |
| `*EventPublisher` | `messaging/` | A broker publisher. Business code never touches one; it writes to `OutboxStore`. |
| `*Config` | `messaging/`, `persistence/`, `configuration/` | Spring `@Configuration`. |
| `*Properties` | wherever it is bound | `@ConfigurationProperties`. |
| `*Payload` | `event/` | An event record. **Never `*Event`** — inside `event/` that says "event" twice, and it collides with `*EventType`. |
| `*EventType` | `event/` | The enum of event names a service publishes. |
| `*Exception`, `*ErrorCode` | `exception/` | Domain failures and their codes. Both live in `exception/`, nowhere else. |
| `*Permissions` | `permission/` | The permission-name constants for one aggregate. |

**`domain/` is the one package with no suffix rule, and that is deliberate.** A domain type is
named for what it is in the business — `User`, `Organisation`, `PillarLink`, `Role`,
`NotificationChannel` — because that vocabulary is the whole point of the layer. `UserModel` or
`UserDomain` would trade the thing worth protecting for a filing convention.

**Where a suffix may be absent elsewhere, the difference must be feature-driven, not naming
drift.** A service without criteria search has no `*Specifications`; a service that stores no
converted column has no `*Converter`. That is correct. What is never correct is one service
calling a thing `*Entity` while another calls the same thing `*JpaEntity`: the concept is
identical, so the suffix is identical. Where a genuine service-specific difference exists, it is
written down at the point of difference — `OutboxEventJpaEntity` documents its `iam_`-prefixed
table because iam shares ipie-user-service's database (EX-001). An undocumented difference is
drift.

### The base package is an identifier, not an address

**`in.gov.ipie` does not track the deployment domain, and does not change when the domain does.**
It is the reversed-domain convention for choosing a namespace, not a reference to one: nothing
resolves it, nothing fetches it, and no part of this platform requires it to match a live host. A
package name whose only job is to be unique has done that job the moment it is chosen.

**Why this is written down rather than left to convention.** The email domain will change at some
point, and the reflex is to rename the package with it. That rename is 460 source files, every
service's `ArchitectureTest.BASE_PACKAGE`, the package `new-service.sh` derives and rewrites, every
class listed by fully-qualified name in an `AutoConfiguration.imports` file, the Maven group and
therefore a platform release with a re-pin in every service — and 38 entries in
`spotbugs-exclude.xml`, which match by exact type name and **fail silently** when they stop
matching. None of that buys a single working behaviour.

**What does change with the domain** is stored data: email addresses in seeded rows and in the
Keycloak realm. `ipie-platform-mca/change-domain.sh` performs that change across every service it
discovers, and its rule is the one Section 8 states — an applied migration is never edited, so the
addresses are moved by a generated migration rather than by rewriting the files that seeded them.

**The package changes only if the owning organisation changes.** `in.gov.ipie` asserts who owns the
namespace, not where the platform is reachable. A new address is not a new owner.

### The User vertical slice, concretely (`ipie-service-template`, the reference; `ipie-user-service` follows the identical shape plus its registration/pillar-linking extension)

| Concern | Class |
|---|---|
| Domain model | `domain/User.java` (JavaBean getters — see note below) |
| Domain status/sort enums | `domain/{UserStatus, UserSortField, UserSearchCriteria}.java` |
| Repository port | `repository/{UserRepository, UserSearchIndex}.java` |
| Domain errors | `exception/{UserNotFoundException, UsernameAlreadyExistsException, EmailAlreadyExistsException}.java` |
| Use-case commands | `command/{CreateUserCommand, UpdateUserCommand}.java` |
| Event-type enum | `event/UserEventType.java` |
| Application service | `service/{UserService, UserServiceImpl, UserValidationAspect}.java` |
| JPA entity | `persistence/UserJpaEntity.java` — `public` (JPA requires it) |
| Spring Data interface | `persistence/{UserJpaRepository, UserJpaRepositoryCustom, UserJpaRepositoryCustomImpl}.java` — by convention only `UserRepositoryImpl` touches these directly |
| Repository adapter | `persistence/UserRepositoryImpl.java` |
| Persistence mapper | `persistence/UserPersistenceMapper.java` |
| Dynamic search | `persistence/UserSpecifications.java` |
| Search document | `search/UserDocument.java` |
| Search Spring Data interface | `search/UserDocumentRepository.java` |
| Search index port impls | `search/{ElasticsearchUserSearchIndex, JpaUserSearchIndex}.java` |
| Search mapper + wiring | `search/{UserSearchDocumentMapper, UserSearchIndexConfig}.java` |
| Event publisher impls + wiring | `messaging/{KafkaEventPublisher, RabbitEventPublisher, LoggingEventPublisher, EventPublisherConfig}.java` |
| Event consumer wiring | `messaging/{EventConsumerConfig, RabbitConsumerConfig}.java` — Kafka and RabbitMQ each need their own consumer-side config |
| Outbox pattern | `messaging/{OutboxEventEntity, OutboxEventJpaRepository, JpaOutboxStore, OutboxRelayScheduler}.java` |
| Event consumer impls | `messaging/{UserEventLogConsumer, RabbitUserEventLogConsumer, JpaProcessedEventStore, ProcessedEventEntity, ProcessedEventJpaRepository}.java` |
| Controller | `controller/UserController.java` |
| MapStruct mapping | `mapper/UserApiMapper.java` |

**`persistence/`, `search/`, and `messaging/` are each one flat package, not split into
artifact-type subpackages (`entity/`, `repository/`, `mapper/`, ...).** Everything
above lives directly under `persistence/` (11 files in `ipie-service-template` today: entities,
Spring Data interfaces, the custom-query impl, the persistence mapper, and `UserSpecifications`,
all as siblings) — visibility stays whatever each class needs for its actual callers (`UserJpaEntity`
`public` because JPA requires it; the rest `public` mainly so `UserRepositoryImpl` can reach them).
**By convention, only the matching `*RepositoryImpl` references its `*JpaRepository`/mapper/
specification** — code review enforces this, `ArchUnit` does not (there's no rule constraining
intra-`persistence` calls). If a service accumulates several entities, group new classes into this
same flat package: everything for every entity lands in it. Same story for `search/` (5 files: document,
Spring Data repository, two search-index port impls, mapper, plus `UserSearchIndexConfig`) and
`messaging/` (14 files: publishers, consumer wiring, outbox, consumer impls, all flat).

**`idempotency/` exists in no service.** A per-service
`IdempotencyKeyEntity`/`IdempotencyKeyRepository`/`IdempotencyService`/`IdempotencyAspect` set looks
like a flat, self-contained concern and is not one: it is a rebuild of `common-web`'s
`IdempotencyStore`, forbidden by Section 13.1. See Section 3.

**A feature-named package is how the layering quietly stops being enforced.** `ipie-iam-service`
carried a `resolution/` package holding `PillarResolution` (domain
record), `PillarResolutionEntity` (`@Entity`), its Spring Data repository, the service, two
consumers and an event handler — four layers as siblings. Every ArchUnit rule above still passed,
because they match *literal package names*: a domain record sitting next to its own JPA entity in
`resolution/` is invisible to both `domainDoesNotDependOnInfrastructure` and
`apiDoesNotExposePersistenceEntities`. This is the failure mode this section already warns about —
"ArchUnit protects the boundary as long as the boundary exists" — and it is worth restating as a
concrete rule: **group by layer, never by feature.** The package was dissolved into `domain/`,
`persistence/`, `service/`, `messaging/`, plus a new `repository/PillarResolutionRepository`
port and its `persistence/PillarResolutionRepositoryImpl` adapter, mirroring
`RoleRepository`/`RoleRepositoryImpl`. The port was not optional: splitting the packages while the
service still called the JPA repository directly would have meant making `PillarResolutionEntity`
and its Spring Data interface `public` purely to keep a cross-layer call compiling — the opposite of
the convention two paragraphs above. Both stay package-private.

**Why `User` uses `getX()` getters, not fluent `x()` accessors:** MapStruct's automatic
property matching only recognizes JavaBean-style getters. An early version of this class used
fluent no-prefix accessors and MapStruct silently left every field unmapped except the one it
could pattern-match (`isActive()`) — no compile error, just a `UserResponse` with every field
null. This was caught during the build, not left for you to discover; keep JavaBean getters -
now Lombok's `@Getter` (see the Lombok subsection right below) rather than hand-written ones -
on any domain object that is a MapStruct *source*: `ipie-user-service`'s
`UserControllerIntegrationTest` was run for real (Testcontainers PostgreSQL) and a created user's
full field set round-tripped correctly through `UserApiMapper.toResponse`.

### Request flow, method by method: every HTTP verb from `UserController` to the database

Traced directly against `ipie-service-template`'s current source (`UserController`,
`UserServiceImpl`, `UserRepositoryImpl`), not summarized from memory. Two AOP aspects sit in front
of every controller method's body, not shown per-endpoint below to avoid repetition:
`@RequiresPermission(...)` → `PermissionCheckAspect` (`common-security`) checks the caller's JWT
`permissions` claim before the method runs; where noted, `@Idempotent`/`@Auditable` add
`IdempotencyAspect`/`AuditAspect` around the same call.

**`POST /api/v1/users` — create**
1. `UserController.createUser(request)` — `@RequiresPermission(USER_WRITE)`, `@Idempotent`
   (`common-web`'s `IdempotencyAspect` checks the `Idempotency-Key` header against the shared
   `IdempotencyStore` — Redis-backed when `spring.data.redis.host` is set, in-memory otherwise,
   always TTL-bounded; short-circuits with the cached response on a repeat key. It is Redis-backed, never a
   per-service `idempotency_keys` table; see Section 3)
2. `UserApiMapper.toCommand(request)` → `CreateUserCommand`
3. `UserService.createUser(command)` → `UserServiceImpl.createUser` — `@Transactional`, `@Auditable`
   1. `User.createNew(username, email, fullName, phoneNumber)` — pure domain object, no DB row yet
   2. `UserRepository.save(user)` → `UserRepositoryImpl.save(user)`:
      `user.getId() == null` → `UserPersistenceMapper.toNewEntity(user)` → `UserJpaEntity` →
      `UserJpaRepository.save(entity)` (Spring Data JPA) → Hibernate `INSERT INTO users (...)` →
      `UserPersistenceMapper.toDomain(saved)` → `User` (now carrying the DB-assigned id/audit columns)
   3. `UserSearchIndex.index(saved)` → whichever port impl is active (`ElasticsearchUserSearchIndex`
      by default) — indexes the row into the search read model
   4. `enqueueEvent(USER_CREATED, saved)` → `OutboxStore.save(...)` → `INSERT INTO outbox_events`,
      **same transaction** as step 2's entity insert — both commit or both roll back together
   5. On successful return, `@Auditable` → `AuditAspect` → `AuditRecorder` (`OutboxAuditRecorder`)
      → a second `outbox_events` row, the audit trail entry
4. Back in the controller: `UserApiMapper.toResponse(created)` → `UserResponse`
5. `ResponseEntity.created(URI.create(".../" + created.getId())).body(response)` → **`201 Created`**

**`GET /api/v1/users/{id}` — read one**
1. `UserController.getUser(id)` — `@RequiresPermission(USER_READ)`
2. `UserService.getUser(id)` → `UserServiceImpl.getUser` — `@Transactional(readOnly = true)`
   1. `UserRepository.findById(id)` → `UserRepositoryImpl.findById` → `UserJpaRepository.findById`
      (Spring Data) → `SELECT ... FROM users WHERE id = ?`
   2. `.orElseThrow(() -> new UserNotFoundException(id))` if absent — maps to `404` via
      `GlobalExceptionHandler`
   3. `UserPersistenceMapper.toDomain(entity)` → `User`
3. `UserApiMapper.toResponse(user)` → `UserResponse` — **`200 OK`**

**`GET /api/v1/users` — offset search (list/filter)**
1. `UserController.searchUsers(username, email, status, page, size, sortBy, sortDirection)` —
   `@RequiresPermission(USER_READ)`; builds `UserSearchCriteria` + `PageRequest` from query params
2. `UserService.searchUsers(criteria, pageRequest)` → `UserServiceImpl.searchUsers` —
   `@Transactional(readOnly = true)` — delegates straight to `UserSearchIndex.search(...)`, **not**
   `UserRepository` — search reads go through the search-index port (Elasticsearch by default,
   `JpaUserSearchIndex`/`UserSpecifications` as the Postgres-backed alternative implementation)
3. `PageResponse.from(result, userApiMapper::toResponse)` maps each `User` → `UserResponse` —
   **`200 OK`**, `PageResponse<UserResponse>` (includes `totalElements` — the `COUNT(*)` cost
   Section 8's pagination subsection explains)

**`GET /api/v1/users/cursor` — keyset search**
Same shape as offset search, but `UserService.searchUsersAfter` → `UserSearchIndex.searchAfter(...)`.
On the Postgres path specifically (`JpaUserSearchIndex`/`UserRepositoryImpl.searchAfter`): decodes
the opaque `cursor` into a `(createdAt, id)` pair, calls `UserJpaRepository.searchAfter(...)` (a
custom query fragment, `UserJpaRepositoryCustomImpl`, direct `EntityManager`/`CriteriaBuilder` —
deliberately not `JpaSpecificationExecutor`, which always issues a `COUNT(*)`), filtered by
`UserSpecifications.matching(criteria)`, ordered `(createdAt, id)` ascending, fetching `size + 1`
rows to compute `hasMore` without a separate count.

**`PUT /api/v1/users/{id}` — update**
1. `UserController.updateUser(id, request)` — `@RequiresPermission(USER_WRITE)`
2. `UserApiMapper.toCommand(id, request)` → `UpdateUserCommand`
3. `UserService.updateUser(command)` → `UserServiceImpl.updateUser` — `@Transactional`, `@Auditable`
   1. `UserRepository.findById(command.userId())` → ... → `UserNotFoundException` if absent
   2. `user.updateDetails(email, fullName, phoneNumber)` — domain method, mutates in memory only
   3. `UserRepository.save(user)` → `UserRepositoryImpl.save(user)`: `user.getId()` is non-null
      this time → re-fetches the managed entity (`jpaRepository.findById`) →
      `UserPersistenceMapper.copyMutableFieldsOnto(user, entity)` → `UserJpaRepository.save(entity)`
      → Hibernate dirty-checking issues `UPDATE users SET ...`
   4. `UserSearchIndex.index(saved)`, `enqueueEvent(USER_UPDATED, saved)`, `@Auditable` — same
      outbox/audit pattern as create
4. `UserApiMapper.toResponse(updated)` → `UserResponse` — **`200 OK`**

**`DELETE /api/v1/users/{id}` — soft delete**
1. `UserController.deactivateUser(id)` — `@RequiresPermission(USER_DELETE)`
2. `UserService.deactivateUser(id)` → `UserServiceImpl.deactivateUser` — `@Transactional`, `@Auditable`
   1. `UserRepository.findById(id)` → ... → `UserNotFoundException` if absent
   2. `user.deactivate()` — sets `status = INACTIVE` in memory only
   3. `UserRepository.save(user)` — same update path as `PUT` above → `UPDATE users SET status =
      'INACTIVE', ...` — **no row is ever deleted**, matching Section 8's "no hard deletes" rule
   4. `UserSearchIndex.index(saved)`, `enqueueEvent(USER_DEACTIVATED, saved)`, `@Auditable`
3. `ResponseEntity.noContent().build()` — **`204 No Content`**

**`POST /api/v1/users/{id}/reactivate`** follows the identical shape to `DELETE` — `@RequiresPermission(USER_WRITE)`,
`user.reactivate()` instead of `user.deactivate()`, `enqueueEvent(USER_REACTIVATED, saved)` — but
returns `UserApiMapper.toResponse(reactivated)` with **`200 OK`**, not `204`, since the controller
method returns the updated `UserResponse` rather than `ResponseEntity<Void>`.

### Lombok — where it's used, where it isn't

Lombok is available platform-wide (`ipie.java-conventions.gradle`), compile-time only
(`compileOnly`/`annotationProcessor`) — a service consuming a compiled common-lib jar never needs
Lombok on its own classpath just to use the classes it produces, only if it wants to author new
Lombok-annotated classes itself. The choice below is codified once here so it isn't
re-litigated per service; every one of the 11 domain services and the cross-cutting services
follows the same rule, not whatever a given developer prefers that week.

**Records first for immutable DTOs/value objects, not Lombok.** On Java 21, a `record` gives a
constructor, accessors, `equals`/`hashCode`/`toString` natively, with zero dependency and zero
generated-code "magic" to reason about - `PageRequest`, `Cursor`, `FieldError`, every
`*Command`/`*Criteria` type in `ipie-service-template` already follows this. Reach for Lombok's
`@Value` only where a record genuinely doesn't fit - inheritance, or a builder on a
mutable-ish type - not as a default.

**Never `@Data` on a JPA entity.** This is the classic Lombok footgun and it will bite a
data-heavy platform like iPIE specifically: `@ToString` on a bidirectional relationship causes
infinite recursion (or triggers every lazy association to load just to print a log line), and
`@Data`'s generated `equals`/`hashCode` (all fields, including the mutable ones) violates the JPA
identity contract - an entity's hash bucket changes as its fields mutate, breaking anything that
ever put it in a `Set`/used it as a `Map` key while attached to a persistence context. On an
entity, use targeted annotations only - `@Getter`/`@Setter` - and only add
`@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with `@EqualsAndHashCode.Include` on the id
field if the entity actually needs equality (`UserJpaEntity` currently doesn't, and stays on
plain identity equality rather than acquiring this for its own sake - see the entity itself).
`@Data` is a forbidden annotation on anything annotated `@Entity`, full stop.

**The low-risk, high-value sweet spots** - safe to reach for anywhere:
- `@Slf4j` in place of a hand-written `private static final Logger LOG = LoggerFactory.getLogger(...)`
  field - see `common-web`'s `GlobalExceptionHandler`.
- `@RequiredArgsConstructor` in place of a hand-written all-`final`-fields constructor, pairing
  naturally with constructor injection - see `ipie-service-template`'s `UserRepositoryImpl`
  (`@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` there, to preserve the class's
  existing package-private constructor visibility - Lombok defaults to generating a `public`
  one). Fine to combine with a field-level parameter annotation like `@Value` too - the repo-root
  `lombok.config`'s `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value`
  is what makes Lombok actually copy it onto the generated constructor parameter (see
  `ipie-user-service`'s `PillarLinkServiceImpl`, which needs this to stay under Checkstyle's
  `ParameterNumber` limit at 9 collaborators). Without that config line, Lombok silently drops the
  annotation instead of erroring, and Spring fails at startup with `NoSuchBeanDefinitionException`
  for the un-annotated parameter's type - this bit `PillarLinkServiceImpl` for exactly that
  reason before the config line existed, caught via `UserControllerIntegrationTest` actually
  loading the full application context rather than a narrower slice. `UserServiceImpl` still uses
  a hand-written constructor (an earlier, still-valid alternative, from before this config
  existed) - either approach works now; a hand-written constructor remains simpler to read when a
  class needs only one or two `@Value` fields.
- `@Builder` on DTOs/commands that don't fit a record (see the rule above - most do).

**Forbidden, not just discouraged:** `@Data` (or `@ToString`/`@EqualsAndHashCode` without
`onlyExplicitlyIncluded`) on any `@Entity` class; any Lombok annotation whose generated behavior
would differ from what a record already gives for free on a type that could just be a record.

**MapStruct interaction:** any class carrying both Lombok and MapStruct annotations should have
`org.projectlombok:lombok-mapstruct-binding` on the `annotationProcessor` configuration (see
`ipie-service-template/build.gradle`) - it exists to force Lombok's annotation processor to run
before MapStruct's, which otherwise isn't *guaranteed*, and if it ever goes wrong the failure is
silent: MapStruct generates a mapper against fields with no accessors yet, with no compile error
(the same class of failure mode as the JavaBean-getter note just above). `User` (this section,
just above) carries `@Getter` and is `UserApiMapper`'s MapStruct source at the same time,
making it this platform's live combination of the two - tested directly against it: on the
current pinned versions (Lombok 1.18.46, MapStruct 1.6.3, Gradle 9.6.1), the generated
`UserApiMapperImpl` maps every field correctly *even with the binding removed*, so this exact
ordering problem isn't currently reproducible here. Keep the binding anyway - it's the
documented, standard fix for this class of processor-ordering issue, costs nothing, and nothing
guarantees a future Lombok/MapStruct/Gradle version bump won't reintroduce the failure it exists
to prevent; don't treat today's non-reproduction as permission to drop it.

---

## 6. API Standards (as implemented)

| Area | Implementation |
|---|---|
| Base path | `/api/v1/users` |
| Format | JSON |
| Dates | ISO-8601 (`Instant`, serialized by Jackson's default) |
| Pagination | Two styles, chosen per endpoint (Section 8 explains why both exist): offset (`page`, `size`, `sortBy`, `sortDirection` → `PageResponse<T>`) for small/admin listings that need a total count and page-jump; keyset/cursor (`cursor`, `size` → `CursorPageResponse<T>` with `nextCursor`/`hasMore`, no count) for anything that could grow into the millions |
| Errors | One shape everywhere: `ApiError{timestamp, status, errorCode, message, path, traceId, fieldErrors[]}` — including responses produced by a **filter**, before any controller runs. `RateLimitFilter`'s `429` renders `ApiError` (`errorCode: RATE_LIMITED`) and sets `Retry-After` rather than a body of its own. A filter short-circuits ahead of `GlobalExceptionHandler`, so anything rejecting there has to build the body itself — that is exactly where a bespoke shape creeps in. |
| Validation | Jakarta Bean Validation on request DTOs (`@NotBlank`, `@Email`, `@Pattern`, `@Size`); cross-field/business rules raise `ValidationFailedException`/`ConflictException` from the application layer |
| Documentation | springdoc-openapi, `/v3/api-docs` + `/swagger-ui.html`, confirmed generating correctly against the running service |
| Idempotency | `Idempotency-Key` request header on `POST /api/v1/users`, backed by `common-web`'s shared `IdempotencyStore` (Redis-backed when configured, in-memory fallback otherwise — see Section 13.1) — no longer a per-service `idempotency_keys` table, that version was replaced (no TTL, unbounded growth) |

### Endpoints implemented

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/api/v1/users` | `USER_WRITE` | Idempotency-Key supported |
| GET | `/api/v1/users/{id}` | `USER_READ` | |
| GET | `/api/v1/users` | `USER_READ` | filter by `username`/`email`/`status`, offset-paged, `sortBy` an allow-list enum (`UserSortField`) |
| GET | `/api/v1/users/cursor` | `USER_READ` | same filters, keyset/cursor-paged - no total count, stays fast at any depth |
| PUT | `/api/v1/users/{id}` | `USER_WRITE` | |
| DELETE | `/api/v1/users/{id}` | `USER_DELETE` | **soft** delete — flips `status` to `INACTIVE`, never removes the row |
| POST | `/api/v1/users/{id}/reactivate` | `USER_WRITE` | undoes the soft delete |

**This table is `ipie-service-template`'s reference set.** `ipie-user-service` (Section 2) carries
all of it plus Organisation management, registration, and pillar-linking extensions — see
`docs/LLD_User_Service.md` for the authoritative, current endpoint-by-endpoint list (this section
must not carry its own copy of that list — a stale one names a `/resolve` endpoint that no
longer exists here at all - it moved to `ipie-iam-service` per ADR-001, see
`docs/SDD_User_IAM_Services.md`). `ipie-iam-service`'s own endpoints are listed in
`docs/LLD_IAM_Service.md`. Prefer those two documents over duplicating endpoint tables here -
they're revised whenever the API surface actually changes, this section is not.

---

## 7. Security Baseline (as implemented)

- OAuth2 resource server (`spring-boot-starter-oauth2-resource-server`) validates JWT
  signature/issuer/audience/expiry through standard
  `spring.security.oauth2.resourceserver.jwt.*` properties.
- **No default issuer-uri is set.** Real environments *must* supply
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (a Keycloak realm URL); startup fails
  loudly without it rather than silently accepting unauthenticated traffic (master standards
  doc principle: fail startup when mandatory secure config is missing).
- Permissions come from a configurable JWT claim (`ipie.security.permissions-claim`, default
  `permissions`) and are exposed as `PERMISSION_*` Spring Security authorities.
- **Business code checks permissions, not roles**, and does it through
  `PermissionEnforcer.require("USER_WRITE")` rather than `@PreAuthorize`. This was a deliberate
  design change from the first draft: `@PreAuthorize` alone does not respect
  `ipie.security.enabled=false` (the local-dev escape hatch, see `application-local.yml`) because
  method security and the HTTP filter chain are independent — a "permit all" filter chain still
  lets `@PreAuthorize` deny every call. `PermissionEnforcer` checks the same flag the filter
  chain does, so disabling security for local testing actually disables it everywhere.
- `CurrentUserProvider`/`CurrentUser` give application code the caller's id/username/permissions
  without touching `SecurityContextHolder` directly.
- `ipie.security.enabled=false` (see `application-local.yml`) is a **local-development-only**
  escape hatch for running the service with no identity provider at all, clearly commented as
  such in three places (the auto-configuration, the properties class, the profile file). It is
  *not* used by `docker-compose.yml` - that stack runs a real Keycloak instead (below) so it
  exercises the actual secured code path. Activate the `local` profile yourself only for a bare
  `java -jar` run with nothing else running.

### Client IP, `X-Forwarded-For` and trusted proxies

Two things key on the caller's address — `RateLimitFilter`'s bucket, and the audit trail's
client-IP field — and both go through `HttpRequestUtils.clientIp`.

It must not return the **first** entry of `X-Forwarded-For`, which is the part a client
writes. A caller therefore chose the bucket it was throttled in *and* the IP recorded against its
own actions. Measured against `/api/v1/users/verify` (limit 10/min): 14 requests carrying a
rotating `X-Forwarded-For` produced **zero** `429`s, versus 10-then-`429` without the header. For a
platform whose audit trail is evidence, the forgeable audit record is the worse half of that.

The header is now resolved by Tomcat's `RemoteIpValve` before any application code runs
(`server.forward-headers-strategy: NATIVE`, in `ipie-security-defaults.yml`), and `clientIp` simply
returns `getRemoteAddr()`. The valve walks the header **right-to-left**, discarding trusted-proxy
entries, so a client-supplied value never survives. Re-measured after the change: the rotating
header gives 10-then-`429`, identical to no header at all.

- `server.tomcat.remoteip.internal-proxies` defaults to **empty**, deliberately. Tomcat's own
  default trusts every RFC1918 range — inside Kubernetes or ECS that makes *any pod* a trusted
  proxy, far wider than intended.
- Empty means no proxy is trusted and the direct peer is used. That is unforgeable, but behind an
  ingress or gateway every caller presents the *gateway's* address, so all callers share **one
  bucket per rule** instead of one each.
- **Each environment must set `IPIE_TRUSTED_PROXIES`** to the ingress + API gateway CIDR (a regex)
  to recover the real client. Until it is set, every service logs a warning at startup naming the
  consequence — the condition is otherwise invisible until users complain.
- Set it only once the gateway is confirmed to **replace** an inbound `X-Forwarded-For` rather than
  append to it. A gateway that appends lets a client-supplied entry survive the walk.

### Credential handling

**`ipie-iam-service` is the credential authority.** Every password on the platform is hashed with
Argon2id and stored in iam's `user_credentials`, and it enters through exactly three routes, all on
iam's `CredentialController`: `POST /internal/credentials/verify` (service-to-service, HMAC-signed),
`POST /api/v1/credentials/password` (public, authorised by a one-time setup token, because the caller
has no credential yet) and `POST /api/v1/credentials/password/change` (authenticated, and it takes
the account from the JWT subject, never from the body). **Keycloak issues tokens and never stores or
validates a password** - both its login flows reach iam through the SPI authenticators instead.

**A reusable credential never leaves iam.** A password must never be written to the outbox, published
to a broker, persisted in another service's tables, recorded in an audit event, or logged. No service
other than iam handles one at all: a browser posts to iam directly rather than through the service
that owns the surrounding business flow.

**One named synchronous exception: credential verification at login.** The Keycloak SPI calls iam's
`/internal/credentials/verify` while the user waits. It is a single hop that does not pass through
`InterServiceClient`, so it is not subject to that client's bulkhead; it carries its own short timeout
and circuit breaker and **fails closed**; and Keycloak's brute-force detector still counts failures,
because the authenticator reports `INVALID_CREDENTIALS` normally. Treat it as the one stated exception
to the no-synchronous-crossing rule, not as precedent for another.

**Single-use, short-TTL secrets may travel on events** - provided they are single-use, short-lived,
masked in logs and audit, and **stored hashed**. This qualifies the rule above rather than weakening
it: the registration OTP already reaches ipie-communication-service this way, and so does the
credential setup token on `ACCOUNT_CREDENTIAL_SETUP_REQUESTED`. A stated absolute would have been a
rule the shipped code already broke, which is worse than a precise one. Hashed at rest means the
setup token as SHA-256 and the registration secrets - verification token, email OTP - as peppered
HMAC-SHA256 through `RegistrationSecretHasher`, never a bare digest: a six-digit code has a million
candidates and a plain hash of one is reversed in under a second.

This is what decides the shape of a feature, not a style preference. Making account provisioning
asynchronous meant the obvious implementation - putting the registration event on the outbox - would
have written a plaintext password into a Postgres row and into every broker relaying it. The design
that avoids it creates the Keycloak account **without credentials** and emails the registrant a
set-password link afterwards. Note that registration sends more than one mail and they do not go to
the same place: the OTP mail verifies the registrant's own address, the set-password mail goes to the
registrant once the account exists, and the approval mail goes to the address configured for that
purpose. Resolve the recipient through `findEmailByPurpose` before designing a flow around it.

`AuditValueMasker` masks `password`, `otp`, `token` and similar field names before an audit event is
serialised. Treat it as a backstop, not the control: a
credential should never reach an audit event in the first place, so a newly-masked field is a bug to
trace upstream, not the protection doing its job. Note it is applied to **audit events only** - a
business event published through the same outbox gets no masking at all, which is exactly why the
rule above is stated at the platform level rather than left to that class.

`pin` is deliberately absent from that list: in this domain it is the postal code on an address, and
masking it would silently blank a legitimate field in every audit record.

### Password policy

`PasswordPolicy` (common-libs) **is the control**, enforced by ipie-iam-service on both routes that
set a password:

    length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1)
      and notUsername and notEmail

This reverses the earlier arrangement, in which Keycloak's realm `passwordPolicy` was the control and
`PasswordPolicy` a mirror of it for early, actionable validation in request DTOs. Keycloak no longer
stores or validates a password, so on the login path there is nothing left for a realm policy to
enforce; if iam does not reject a weak password, nothing behind it will.

The realm keeps the identical policy string, and the two must be kept identical, because it still
governs any credential set through Keycloak's own admin API or account console. **Closing those
remaining paths - disabling the reset-credentials flow, removing the `UPDATE_PASSWORD` required
action, and turning off account-console password management - is Stage 2 of `ARCHITECTURE_WORKING_PLAN.md`
and is not yet done.** Until it is, a password can be set inside Keycloak that iam knows nothing
about, and that account then fails login: the SPI asks iam, and iam has no hash for it.

A realm with **no password policy at all** leaves the only application check as
`@Size(min = 8)`, so `password1` was accepted. The floor is now 12 characters, since length
contributes more strength than the character classes do; the class requirements are kept because
they are a stated requirement for this platform.

Complexity without a limit on guessing is half a control, so the realm also enables brute-force
protection: temporary lockout after 10 failures, backing off to 15 minutes. Deliberately **not**
permanent - a permanent lockout lets anyone lock a citizen out of their own account by failing
logins on their behalf.

### Rate limiting (`RateLimitFilter`) — three things that decide whether it works

Opt-in per service, registered in that service's own `SecurityFilterChain`. It exists for genuinely
public, unauthenticated endpoints, where a per-JWT limit is impossible.

- **The first matching rule wins** (`findFirst`), so a specific pattern must be listed *above* the
  general one that would otherwise swallow it. `ipie-user-service` depends on this ordering:
  `/api/v1/registrations/organisations/search` (60/min — a read a user repeats while typing a
  company name) sits above `/api/v1/registrations/**` (5/min — the write flow). Before they were
  split, searching for an employer exhausted the registration budget before the user could register.
- **Budget a rule against the calls the flow makes, not the user actions it represents.**
  Registration is four calls to that pattern, so 5/min is roughly *one registration per minute* per
  caller — and a single user who mistypes an OTP and asks for a resend trips it.
- **The limiter is per-instance unless Redis is configured.** `RedisRateLimiter` is registered only
  when `spring.data.redis.host` is set; otherwise `InMemoryRateLimiter` applies, N replicas mean N ×
  the limit, and every deploy resets the buckets. Nothing warns you, and the limiter looks like it
  is working.

A rejection answers `429` in the standard `ApiError` shape with `Retry-After` set to the rule's
window (Section 6).

### Verified against a real Keycloak, not just unit-tested

`docker-compose.yml` runs `quay.io/keycloak/keycloak:26.6`, importing
`deploy/keycloak/realm-export.json` at startup: a realm `ipie`, and three
confidential clients — `ipie-service-template`, `ipie-user-service`, `ipie-iam-service` (all
direct-access-grants enabled, so the token endpoint can be hit directly for local testing without a
browser redirect flow) — plus a protocol mapper that turns the caller's realm roles into the
`permissions` claim `PermissionAuthorities`/`PermissionEnforcer` expect. **`ipie-config-service`
and `ipie-communication-service` have no realm client yet**, even though `ipie-communication-service`
has a `docker-compose.yml` entry (Section 2) — confirm token-based auth actually works against
either before relying on it; this is a gap, not a verified-working state. Two seeded users:
`testuser` (`USER_READ`/`USER_WRITE`/`USER_DELETE`) and `readonlyuser` (`USER_READ` only), both
password `testpass`.

This was proven end to end against a standalone Keycloak 26.6.4 (run directly via `bin/kc.sh
start-dev --import-realm` against the same `realm-export.json`, without Docker) running alongside
the real Postgres and Kafka from Section 9:

| Request | Result |
|---|---|
| `GET /api/v1/users` with no token | `401` |
| `POST /api/v1/users` as `testuser` | `201 Created` |
| `POST /api/v1/users` as `readonlyuser` (no `USER_WRITE`) | `403`, common `ApiError` shape, `errorCode: "ACCESS_DENIED"` |
| `GET /api/v1/users` as `readonlyuser` | `200` |

The audit trail for the `testuser` create carried `actorUserId` equal to the JWT's `sub` claim
verbatim - proof `CurrentUserProvider` correctly resolves the real, validated identity, not a
placeholder.

One thing **not** yet re-verified through actual `docker compose up`: the
`KC_HOSTNAME: keycloak` setting that keeps the token issuer consistent whether a client reaches
Keycloak via the container network (`http://keycloak:8080`) or the mapped host port
(`http://localhost:8080`) - this is the standard, documented fix for that class of problem, but
run `docker compose up --build` yourself as the first check before relying on it. (Keycloak's host
port was later moved from an initial `8180` to `8080` - see `docker-compose.yml` - specifically so
this same hostname:port also works from a browser, not just from curl/the API; `ipie-service-template`
moved to `8091` to make room - not `8081`, since McAfee's "Agent Common Services" binds that port
on Windows hosts for its own local IPC, silently intercepting anything a native Windows
client/Postman sends to `localhost:8081` before it ever reaches WSL2's Docker port-forwarding.)

---

## 8. Database & Migration Standards (as implemented)

- Flyway-only schema management; `spring.jpa.hibernate.ddl-auto=validate` — Hibernate can never
  silently alter the schema.
- Standard columns on `users`: `id, created_at, created_by, updated_at, updated_by, version`
  (`AuditMetadata` value object on the domain side; `@CreatedDate/@CreatedBy/@LastModifiedDate/
  @LastModifiedBy/@Version` + `AuditingEntityListener` on the JPA side, wired to
  `CurrentUserProvider` via `JpaAuditingConfig`).
- **No hard deletes** — `DELETE /api/v1/users/{id}` flips `status` to `INACTIVE`; verified the
  row and its history remain queryable afterward.
- Migrations (`ipie-service-template`, the reference set): `V1__create_schema.sql` (extension),
  `V2__create_tables.sql` (`users` + `idempotency_keys`, the latter dropped again by
  `V9__drop_idempotency_keys.sql` — see Section 3), `V3__create_indexes.sql` (status filter,
  case-insensitive email lookup), `V4__create_processed_events_table.sql`,
  `V5__create_outbox_events_table.sql`, `V6__create_documents_table.sql`,
  `V7__add_users_keyset_pagination_index.sql` (`(created_at, id)` composite, for keyset paging
  below). Each real service continues its own `V` sequence from this same base once it diverges -
  **version counts below are a point-in-time snapshot, already well behind current** (both
  `ipie-user-service` and `ipie-iam-service` have grown substantially past what's listed here
  since - Organisation, the User/IAM auth-boundary split, Redis idempotency's table-drop
  migration, the audit-trail tables, and more). Don't rely on the specific `V` numbers in this
  bullet for either service - read each service's own `src/main/resources/db/migration/`
  directory directly, it's always authoritative. `ipie-communication-service` skips `V3` (no
  keyset index needed for its domain) and adds `V6__create_notification_tables.sql`,
  `V7__seed_dummy_recipients.sql`; `ipie-config-service` matches the template's set unchanged
  (`V1`-`V7`, including the Document slice's `V6`).
- Optimistic locking via `@Version`; confirmed the version counter increments across the
  update → deactivate sequence in manual testing.

### Pagination — offset vs. keyset, and why both exist (as implemented)

**The problem.** `PageRequest`/`PageResult`/`PageResponse` (`common-core`/`common-web`) is classic
offset paging (`page`/`size`, translated to Spring Data JPA's `Pageable` → SQL `LIMIT ? OFFSET ?`).
It does not scale: `OFFSET` gets linearly slower the deeper a caller pages, because the database
still has to walk and discard every skipped row before it can return anything, and pairing it with
`page.getTotalElements()` means every single call also pays for a full `COUNT(*)` over the same
filtered query. Harmless on a small table; on one with hundreds of millions of rows, both costs
compound into requests that get steadily slower the further a caller pages, and a `COUNT(*)` that
is itself a near-full scan whenever the filter isn't well-indexed.

**The fix is a second, additive paging mechanism, not a replacement.** Offset and keyset paging
answer genuinely different questions - one endpoint can't do both jobs:

| Need | Use | Cost |
|---|---|---|
| "Page 3 of 47", jump to an arbitrary page, a total-results count (admin/reporting screens) | Offset - `PageRequest`/`PageResult`/`PageResponse` | `COUNT(*)` every call; slower the deeper you page |
| Infinite scroll, sync/export jobs, any listing that could grow into the millions | Keyset ("seek") - `CursorPageRequest`/`CursorPageResult`/`CursorPageResponse` | No count at all; equally fast on page 1 and page 100,000; forward-only, no page-jump |

Replacing offset paging with keyset everywhere would silently remove page-jump/total-count from
every admin screen that legitimately needs it; keeping offset-only leaves no scalable path for
listings that need one. Both stay, chosen per endpoint.

**How keyset pagination is implemented.** `CursorPageRequest`/`CursorPageResult` carry `cursor`
(opaque, `null` for the first page) and `size` in place of `page`/`totalElements`; a client walks
every page by feeding each response's `nextCursor` back as the next request's `cursor` until
`hasMore` is `false` - see `Development_Environment_Configuration.md`'s "Search / List Users
(keyset/cursor pagination)" section for the full request/response walkthrough. The cursor itself
is a `(createdAt, id)` tuple (`common-core`'s `Cursor`) - every aggregate already carries both
audit/identity columns (see this section's audit-column standard above), making the pair a
universally available, stable sort/tiebreak key with no per-entity sequence column needed. The
query fetches `size + 1` rows to detect `hasMore` without a separate count, and is always ordered
`(createdAt, id)` ascending - non-negotiably, since that fixed order is what lets the query use a
plain index range scan instead of a sort.

`ipie-service-template`'s `UserController`/`UserRepositoryImpl`/`ElasticsearchUserSearchIndex`
(`GET /api/v1/users/cursor`) are the reference implementation for a new entity to copy:
- **Postgres** (`UserRepositoryImpl.searchAfter`): a direct `EntityManager`/`CriteriaBuilder`
  query via a custom `UserJpaRepositoryCustom`/`Impl` repository fragment - deliberately *not*
  `JpaSpecificationExecutor.findAll(Specification, Pageable)`, which always issues a `COUNT(*)`
  regardless of whether the caller ever reads `totalElements`. Backed by the
  `idx_users_created_at_id` index (`V7__add_users_keyset_pagination_index.sql`) - a new entity
  copying this pattern needs the equivalent `(created_at, id)` composite index, or the query
  degrades to the same full-scan cost keyset pagination exists to avoid.
- **Elasticsearch** (`ElasticsearchUserSearchIndex.searchAfter`): `search_after` sorted by
  `createdAt`/`idSort` - `idSort` duplicates the `@Id` field as a regular `Keyword` field (same
  dual-field pattern `UserDocument` already uses for `username`/`usernameLower`), because `@Id`
  alone only becomes Elasticsearch's `_id` metafield, which isn't reliably usable as a sort key.

**Elasticsearch mapping changes need a fresh index, not just a code change - verified the hard
way.** Spring Data Elasticsearch only applies a `@Document`/`@Field`-derived mapping when it
*creates* an index; adding `idSort` to `UserDocument` after the `users` index already existed
(from before this feature existed) did not retroactively fix that index's mapping. The first
document indexed afterwards silently added `idSort` back via Elasticsearch's own dynamic mapping
instead - as `text` with an auto-generated `.keyword` sub-field, not the plain top-level
`keyword` field `@Field(type = FieldType.Keyword)` asks for - and `search_after` sorting on a
`text` field with no fielddata fails outright (`search_phase_execution_exception`, all shards
failed). This is invisible in `UserControllerIntegrationTest` (Testcontainers always starts from
a brand-new, empty index, so the mapping is always correct there) and only surfaced testing this
feature against the project's actual long-lived local Elasticsearch instance. Fix for an index
that predates a `UserDocument` field change: delete the index (`DELETE /users`) and let it
recreate on next startup - fine for local dev/search-is-a-read-model-not-source-of-truth, but a
real environment needs an explicit reindex step (Elasticsearch has no Flyway-equivalent
migration mechanism) whenever `UserDocument` gains a new sortable/filterable field after its
index already has data.

---

## 9. Observability, Audit & Events (as implemented)

**Structured logging.** Every log line is a single JSON object (via `logstash-logback-encoder`),
carrying `service`, `environment`, and — when present — `correlationId`, `traceId`, `spanId`,
`userId`, `caseId` from the MDC. The JSON shape itself lives once in `common-observability`
(`META-INF/ipie/logback-json-console-appender.xml`); each service's own `logback-spring.xml` just
includes it. Verified in a real run:
```json
{"@timestamp":"...","message":"Tomcat started...","service":"ipie-service-template","environment":"local"}
```

**Log aggregation — ELK.** Structured logging is only half a control without collection: a
service emitting a well-formed JSON line that nothing reads leaves the only way to
read a log was `docker compose logs`, per container, lost on recreate; correlating one request
across four services meant grepping four separate outputs. `logstash-logback-encoder` on the
classpath had misled more than one reader into assuming an ELK deployment existed - it is an
in-process JSON *formatter*, named for the format's origin, and it opens no socket.

The pipeline now is:

```
service (LOGSTASH_TCP appender)  ->  logstash:5044  ->  elasticsearch-logs:9200  ->  kibana:5601
```

**Why the services push, rather than a shipper tailing their stdout.** The conventional design
(Filebeat, Fluent Bit, or the OTel collector's `filelog` receiver reading
`/var/lib/docker/containers`) is impossible in this environment, and it is worth recording why so
it is not re-proposed. On Docker Desktop's WSL2 backend the daemon's container-log directory lives
inside the `docker-desktop` VM and is not reachable from a container by any bind mount. Verified by mounting both `/var/lib/docker/containers` and
`/run/desktop/mnt/host/var/lib/docker/containers`: each yields an **empty** directory, and mounting
the first silently *creates* an empty `/var/lib/docker/containers` on the Ubuntu side, which makes
it look like it half-worked. A Filebeat-based version was built first and discarded on this finding.

So the transport is `LogstashTcpSocketAppender` - which ships in `logstash-logback-encoder`, the
JAR `common-observability` already depended on, so the binding needed **no new dependency**, only
configuration. It is asynchronous (ring buffer on its own thread): if Logstash is down it retries in
the background and drops events once the buffer fills rather than blocking a request thread, and
`JSON_CONSOLE` still has every line regardless. Logstash is doing transport, not parsing - the parse
work was already done in-process by `LogstashEncoder`, which is why its codec is `json_lines` and
there is no grok in `deploy/logstash/pipeline/logstash.conf`.

**Opt-in via the `elk` Spring profile.** The appender and its root attachment live entirely in
`common-observability`'s `logback-json-console-appender.xml`, inside `<springProfile name="elk">`;
no service's own `logback-spring.xml` mentions ELK at all. `docker-compose.services.yml` sets
`SPRING_PROFILES_ACTIVE=elk`; host-JVM runs (`start-ipie.sh`) deliberately do not, since a service
with no Logstash to reach would reconnect-warn forever. **This is the platform's first Spring
profile** - anything that later needs one must *append* to that value, not replace it, or log
shipping silently stops.

Two traps found while building it, both of which fail silently:

1. **`<springProfile>` cannot be nested inside `<root>`, `<logger>` or `<appender>`.** Spring Boot
   logs `springProfile elements cannot be nested within an appender, logger or root element` as a
   WARN through Logback's own status printer - not through the application log - and then ignores
   the block. The appender is defined and never attached; not one line ships, and every container
   looks healthy. Nesting the other way round (`<root>` inside `<springProfile>`) is the supported
   form and is what this file does.
2. **Do not "promote" `service` to `service.name` in the Logstash filter.** Logstash reads
   `service.name` as a *nested* field while the encoder already sets `service` as a plain string,
   so Elasticsearch rejects **every** document with `can't merge a non object mapping [service]
   with an object mapping` - a 400 per event, nothing indexed, and the only evidence is in
   Logstash's own log. `service` as a scalar is perfectly queryable.

**Verified end to end, not merely started.** A request carrying
`X-Correlation-Id: elkverify4-1787585545` against
`/api/v1/registrations/organisations/search` produced five lines, all five of which were found in
`ipie-logs-2026.08.24` carrying `correlationId`, `traceId`, `spanId`, `service` and `environment`
intact. `traceId` surviving the trip is what makes a Jaeger span and its log lines joinable.

**A gap this exposed, worth acting on separately.** The four services contain **zero**
`log.info/warn/error/debug` statements in their own code (`ipie-common-libs` has 7). Everything
currently shipped is framework logging - Spring, Hibernate, RabbitMQ. The pipeline is real and
correct, but there is almost nothing application-specific flowing through it, and a successful
request logs nothing at all. Verifying the pipeline required temporarily raising
`logging.level.org.springframework.web` to DEBUG to make any in-request line exist. Deciding what
the services *should* log - and at which level - is a separate piece of work, and is what will make
this pipeline worth querying.

**Not done, deliberately.** No ILM policy or retention (indices are daily, `ipie-logs-YYYY.MM.dd`,
deleted by dropping an index); no TLS or authentication on either logs-tier component; Kibana logins
are Elasticsearch-native because **SSO via OIDC/SAML is an Elastic Platinum feature**, as is
Elasticsearch audit logging - see "Third-party licences and paid tiers" in Section 12. The second
Elasticsearch (`elasticsearch-logs`, host port 9201) is deliberately separate from the
`elasticsearch` on 9200 that backs the "search users" read path: log-ingest volume must not compete
with a product feature for heap and disk, and a log-driven disk-full must not take user search down.

**OpenTelemetry Collector.** Services export OTLP to the collector rather than straight to Jaeger (`otel-collector:4318` in-network,
`localhost:4318` from the host — it holds the OTLP ports, so
`management.otlp.tracing.endpoint` keeps its shape and Jaeger now publishes only its UI on 16686).

It is worth a hop that a direct export does not give:

- **Redaction.** Personal data is stripped once, before telemetry leaves the service trust boundary,
  rather than each service getting it right in-process — spans carry user ids and identifiers in URL
  paths, and this is a DPDP obligation.
- **Tail sampling.** "Keep every trace that errored" cannot be expressed with head sampling: the
  service decides before it knows the outcome. Only something seeing the whole trace can. The
  baseline stays at 100% locally, matching the template's head sampling.
- **Swappability.** Moving to Tempo or a managed backend changes one file, not every service.

Verified with real traffic rather than a clean startup: 29 traces reached Jaeger through the
collector, 8 of them HTTP request traces, against a freshly recreated Jaeger whose storage is
in-memory.

**Tomcat thread metrics.** `server.tomcat.mbeanregistry.enabled` is off in
Spring Boot by default, and without it Micrometer publishes no `tomcat.threads.*` at all — only
`tomcat.sessions.*`. That gap hides the one signal showing a service saturating: busy threads
approaching max, with requests queueing behind them. Latency alone cannot separate "the downstream
is slow" from "this service is out of threads". `ipie-observability-defaults.yml` turns it on for
every service; `tomcat.threads.busy`, `.current` and `.config.max` are now scraped by Prometheus.

**Correlation id.** `CorrelationIdFilter` reads `X-Correlation-Id` (generating one if absent),
**rejects/replaces any value that doesn't match `[A-Za-z0-9-]{1,64}`** rather than echoing it
back verbatim — this was a real finding from SpotBugs (`HRS_REQUEST_PARAMETER_TO_HTTP_HEADER`,
an HTTP response-splitting vector) that was fixed, not suppressed.

**Distributed tracing (OTel + Jaeger).** `common-observability` depends on
`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`, and `application.yml` sets
`management.tracing.sampling.probability` (default `1.0`, dial down for real traffic volume) and
`management.otlp.tracing.endpoint` (default `http://localhost:4318/v1/traces`, overridden to the
compose network's `jaeger` host by `IPIE_OTLP_ENDPOINT` in `docker-compose.yml`, which now runs a
`jaegertracing/all-in-one:1.65.0` container (the bare `1.65` tag doesn't exist on Docker Hub) with
its OTLP receiver on 4317/4318 and its trace-viewer UI on 16686). This replaces the previous Brave/Zipkin wiring, which has been fully removed (not
just disabled) from `common-observability/build.gradle`, `application.yml` and
`docker-compose.yml`. Same verification approach as before applies here: boot the service against
a standalone Jaeger, make a request, confirm `ipie-service-template` shows up in Jaeger's UI with a
`traceId` matching the app's own JSON log lines.

**Audit.** `@Auditable` + `AuditAspect` records one `AuditEvent` per successful business
operation, capturing who (from `CurrentUserProvider`), what, when, source IP, service name,
entity id and correlation id. `UserService` demonstrates it on create/update/deactivate/
reactivate. Verified output:
```json
{"eventType":"BUSINESS","action":"USER_CREATED","entityType":"USER","entityId":"...","actorUserId":"system","sourceIp":"127.0.0.1","serviceName":"ipie-service-template","correlationId":"..."}
```
`ipie-service-template` runs on the durable `OutboxAuditRecorder` today, not the logging
fallback — `AuditAutoConfiguration` prefers it automatically once a service has an `OutboxStore`
bean (`JpaOutboxStore` here), writing audit events through the same transactional outbox as
domain events rather than just to a logger. `LoggingAuditRecorder` remains the **reference
fallback** for a service with no `OutboxStore` bean yet — it writes to the `AUDIT` logger, not a
persistent, queryable store, and is not a substitute for the outbox-backed path once one exists.

**Events (Kafka, with a RabbitMQ standby - real, not a stub) - published via the transactional
outbox pattern.** `EventEnvelope` matches the standard shape (`eventId, eventType, eventVersion,
occurredAt, source, correlationId, caseId, data`). Business code never talks to the broker
directly: `UserService` writes `USER_CREATED/UPDATED/DEACTIVATED/REACTIVATED` (contract version 1)
to `OutboxStore` (common-events) inside the *same* `@Transactional` boundary as the entity save,
so the DB row and the outbox row either both commit or both roll back - no dual-write race between
Postgres and the broker. `JpaOutboxStore` (in `ipie-service-template`) is the reference
implementation, backed by the `outbox_events` table (migration `V5`).

A separate poller, `OutboxRelayScheduler` (`@Scheduled`, `ipie.events.outbox.relay-interval-ms`,
default 5s, catches and logs transient failures rather than letting them surface as unhandled
stack traces), drains unpublished rows via `OutboxRelay` (common-events, framework-agnostic
orchestration - a service supplies its own scheduling) into whichever `EventPublisher` is active.
`EventPublisherConfig` picks that implementation by precedence: `KafkaEventPublisher` when
`spring.kafka.bootstrap-servers` is configured, else `RabbitEventPublisher` when
`spring.rabbitmq.host` is configured instead - a standby broker provisioned in `docker-compose.yml`
in case Kafka doesn't get organizational clearance, switchable with **no code change** - else
`LoggingEventPublisher` (logs to `EVENTS`). The two real bindings are mutually exclusive by design
(`@ConditionalOnMissingBean(EventPublisher.class)` on the RabbitMQ bean means Kafka always wins if
both happen to be configured, which should never happen in practice). The relay runs regardless of
which publisher is active, so the outbox guarantee holds the same way in every environment. Each
Kafka record is keyed by the affected entity's id (falling back to `eventId` if the payload is
null) so events about the same entity land on the same partition and a consumer sees them in
order; the RabbitMQ binding routes by `event.eventType()` on a topic exchange instead, the
closest RabbitMQ analogue.

The relay only guarantees **at-least-once** delivery (a crash between a successful `publish()` and
marking the row published re-sends it next pass) - this is why it must always be paired with
consumer-side idempotency, which this template demonstrates end-to-end for **both** brokers:
`UserEventLogConsumer` (`@KafkaListener`) and `RabbitUserEventLogConsumer` (`@RabbitListener`,
bound to a queue declared by `RabbitConsumerConfig`) each use `IdempotentEventHandler` +
`JpaProcessedEventStore` (backed by `processed_events`, migration `V4`) to guarantee each event is
processed exactly once regardless of redelivery. Together, the outbox and the idempotent consumer
give reliable, effectively-exactly-once processing on top of at-least-once delivery on the wire.
Both consumer classes are gated with `@ConditionalOnProperty` **at the class level**, not only via
their respective container-factory beans - a real bug found while adding the RabbitMQ side: Spring
Boot's own Kafka/RabbitMQ auto-configuration each supply a default listener container factory
whenever the corresponding starter is merely on the classpath, so an unconditional `@Component`
would still bind and attempt a connection (to `localhost:9092`/`localhost:5672`) even when that
broker isn't the one actually configured.

**Verification status:** `OutboxRelay`, `UserEventLogConsumer` and `RabbitUserEventLogConsumer`
each have a direct unit test proving the core logic (`OutboxRelayTest`,
`UserEventLogConsumerTest`, `RabbitUserEventLogConsumerTest`), and `JpaOutboxStore` is exercised
for real against Testcontainers PostgreSQL via `UserControllerIntegrationTest`'s existing
create/update flows (every call now writes an outbox row).

**Both broker paths have since been live-verified end to end**, the same way, against the default
`docker-compose` stack (see Section 11, "Switching the event broker"): created a user through the
real API and confirmed every stage for each broker - `outbox_events` row written → relay marked it
published ~4s later → the consumer (`UserEventLogConsumer` for Kafka, `RabbitUserEventLogConsumer`
for RabbitMQ) logged the exact same event id and user id → `processed_events` recorded the
idempotency marker. The RabbitMQ run additionally confirmed a clean revert back to Kafka
afterward, with zero leftover RabbitMQ activity. Nothing about this flow remains unverified for
either broker.

---

## 10. Code Quality Gates (as wired)

| Check | Status |
|---|---|
| Compile | ✅ every module, JDK 21 |
| Checkstyle | ✅ wired, two configs (`checkstyle.xml` main / `checkstyle-test.xml` relaxed for test-naming conventions), runs clean |
| SpotBugs | ✅ wired, runs clean — and caught two real bugs during this build (CRLF injection risk in the correlation filter; a mutable-field exposure pattern) |
| JaCoCo | ✅ wired, report generated per module |
| Unit tests | ✅ present in every module, including `ipie-keycloak-spi` (`HmacRequestSignerCrossCheckTest` — proves its hand-written, JDK-only HMAC signing stays byte-identical to `common-security`'s real `HmacSignature`, since this module cannot depend on `common-security` at runtime; `common-security` is a test-only dependency there). Do not rely on any specific test count in this section; read the module's own results instead. |
| Architecture tests | ✅ `ArchitectureTest` (10 ArchUnit rules: 6 layering + 4 binding-rule enforcement — see Section 13.2), bound as one `LayeredArchitectureRules.all(BASE_PACKAGE)` field per service, present and passing in **every** service. Confirm it by opening the file, not by trusting this row: a service whose `ArchitectureTest` is absent passes every rule silently, and nothing else reports it. |
| Integration tests | ✅ written and verified passing (`UserControllerIntegrationTest`, 7 scenarios via Testcontainers PostgreSQL/Elasticsearch, covering CRUD, idempotency, offset and keyset pagination, and sort-field validation) — requires a local JDK and a real Docker daemon reachable from it; see Section 11's "Prerequisites" for the setup that makes this work on both Ubuntu/WSL and Windows |
| Dependency/secret/container scan | ❌ not wired — see Section 12 |
| SonarQube | Template only (`ipie-quality-config/sonar/sonar-project.properties`) — no server/token wiring |

Root convenience task: `./gradlew qualityCheck` runs `check` (compile + test + checkstyle +
spotbugs) across every module.

### Guidelines beyond automated tooling — developers must apply these directly

Checkstyle/SpotBugs/JaCoCo/ArchUnit/SonarQube catch a real, useful slice of problems, but none of
them can verify *design judgment* - whether a class has too many responsibilities, whether a
security control is actually the right one for the threat, whether an API is intuitive to a
consumer. That judgment is the developer's job, guided by the named standards below - these are
expectations to apply on every change, not a checklist this repository claims to have "finished."

- **SOLID principles** (Single Responsibility, Open/Closed, Liskov Substitution, Interface
  Segregation, Dependency Inversion) - the working test for Single Responsibility on this
  codebase: if you can't summarize a class's job in one sentence without "and," it's doing too
  much. Dependency Inversion is why `domain`/`application` depend on ports (`UserRepository`,
  `EventPublisher`, `FileStorage`, `AuditRecorder`) and never on a concrete JPA/Kafka/S3 class
  directly (Section 5) - keep new code the same way, not because ArchUnit enforces every
  instance of it, but because it's the actual reason the pattern exists.
- **OWASP Top 10** (owasp.org/Top10) - the standard reference for web-application security risks
  (broken access control, injection, cryptographic failures, security misconfiguration, etc.).
  Review new endpoints/features against it, not only against whatever `common-security` already
  enforces by default.
- **OWASP API Security Top 10** - the API-specific companion to the above (broken object-level
  authorization, excessive data exposure, lack of rate limiting, mass assignment, ...) -
  particularly relevant here since this platform is API-first; check a new endpoint against this
  list specifically, not just the general Top 10.
- **OWASP ASVS** (Application Security Verification Standard) - a more rigorous, checklist-style
  verification standard than the Top 10, worth applying to any genuinely high-sensitivity
  endpoint (CIRP/Liquidation/PGIRP-style state transitions) beyond the platform's default
  security baseline (Section 7).
- **CWE/SANS Top 25 Most Dangerous Software Weaknesses** - a code-level complement to OWASP's
  request/response-level focus; useful when reviewing a specific function rather than an
  endpoint's overall design.
- **SEI CERT Oracle Coding Standard for Java** - the source for several fixes already made this
  way in this codebase (e.g. `CachedBodyHttpServletRequest` sealed as `final` specifically for
  CERT rule OBJ-11, `CT_CONSTRUCTOR_THROW`) - apply the same standard to new code, not only where
  SpotBugs happens to catch a violation of it.
- **The Twelve-Factor App** (12factor.net) - config via environment variables, logs as an event
  stream, strict dev/prod parity - matches this platform's own conventions already (env-var
  overrides throughout `docker-compose.yml`, structured JSON logs to stdout); keep new services
  the same way rather than reintroducing config files or bespoke logging setups.
- **GIGW** (Government of India Guidelines for Indian Government Websites) and **WCAG 2.1 AA** -
  accessibility and usability requirements specifically expected of a government-facing platform;
  applies to `ipie-web` and any future citizen/stakeholder-facing frontend, not just internal
  tooling.
- **DPDP Act, 2023** (India's Digital Personal Data Protection Act) - governs how personal data
  (which, on this platform, includes Aadhaar/PAN - see `common-utils`' `DataMasking`) may be
  collected, processed, masked and retained; a real legal obligation, not merely good practice,
  for any field or feature touching personal data.
- **CERT-In guidelines** - India's national cybersecurity incident-response and compliance
  authority; already the reference point for the cipher-suite/data-residency items in
  `Infra_Environment_Configuration.md`'s Section 12 - apply the same lens to any new
  security-relevant infrastructure decision.

None of the above is something a build task can "pass" or "fail" - they're professional judgment
calls informed by named, citable standards, applied deliberately on every change, the same way a
senior reviewer would ask "did you consider X" in a code review.

---

## 11. Running It Yourself

**Start here: `ipie-platform-mca/start-stack.sh`.** It runs a preflight, starts the infrastructure
and the three services, waits for health, and finishes with a real login — the only check that
proves the whole chain (grant → Keycloak → SPI → HMAC → iam → Argon2id → token).

```bash
# from WSL, never PowerShell - see the runbook for why
cd ipie-platform-mca
./start-stack.sh            # start everything and verify it
./start-stack.sh doctor     # diagnose only, ~10 seconds, changes nothing
./start-stack.sh smoke      # re-test login against a running stack
```

**When it will not come up, read `docs/LOCAL_STACK_RUNBOOK.md`** before debugging application code.
Every failure in it presents as a plausible application bug and is not one — most of all
`503 temporarily_unavailable` at the token endpoint, which is the SPI failing *closed* because it
could not reach iam. `doctor` names the cause in seconds; the runbook has the copy-pasteable fix for
each, including WSL mirrored-networking (which silently stops Docker publishing ports to Windows),
the `${HOME}/.m2` build-context failure when compose is run from PowerShell, Keycloak's leaked-JVM
H2 lock, and the realm import that wipes user role mappings.


### Prerequisites — same result on Windows and Ubuntu/WSL

`./gradlew build` needs a **local JDK 21 install** (Eclipse Temurin, the same vendor Docker/CI
use). Running it *inside* a bare `eclipse-temurin:21-jdk` container instead of natively works for
compile/checkstyle/SpotBugs, but breaks two of the test gates:

- **Mockito's inline mock maker self-attaches a Java agent to its own JVM at runtime.** Docker's
  default seccomp profile blocks the `ptrace` syscall this needs, so every Mockito-backed unit
  test fails with `Could not initialize inline Byte Buddy mock maker` unless the container is
  started with `--cap-add=SYS_PTRACE` — and some hosts still block it even then.
- **Every Testcontainers-based test** — not just `UserControllerIntegrationTest`
  (PostgreSQL/Elasticsearch), but every `RedisIntegrationTest`-backed one too
  (`common-cache`'s `IpieCacheAutoConfigurationTest`, `common-security`'s `RedisNonceStoreTest`,
  `common-session`'s `RedisSessionStoreTest`) — starts *sibling* containers via the host Docker
  socket. If the build is itself running inside a container (Docker-outside-of-Docker), those
  sibling containers usually can't call back into the build container's JVM (`Could not connect to
  Ryuk`, `Could not find a valid Docker environment`, or `Timed out waiting for container port to
  open` even with the socket mounted and Ryuk disabled) — a well-known nesting limitation, not a
  defect in the code or the tests. **Mounting `/var/run/docker.sock` into the build container and
  setting `TESTCONTAINERS_RYUK_DISABLED=true` is not a reliable fix** - it was tried against this
  exact set of tests and still failed with a port-reachability timeout, because the build
  container's own network namespace has no route back to the Docker bridge gateway
  (`172.17.0.1`) the sibling container publishes its port on. The only fix that reliably works is
  the one below: run Gradle on a machine with Docker installed *directly* (not nested), so the
  test JVM and the containers it starts are both real siblings on that machine's actual Docker
  network.

Installing the JDK locally avoids both:

| OS | Install | Verify |
|---|---|---|
| Ubuntu / WSL2 | `sudo apt install openjdk-21-jdk` (or [SDKMAN](https://sdkman.io): `sdk install java 21.0.11-tem`) | `java -version` |
| Windows | [Eclipse Temurin 21 MSI](https://adoptium.net) — the installer sets `JAVA_HOME`/`PATH` for you | `java -version` in a new terminal |

With a native JDK and Docker Desktop's WSL2 integration enabled (`docker info` should print without
error from the same shell), the Testcontainers-backed tests need no special flags at all - Docker
Desktop already exposes a real, non-nested daemon to the WSL2 distro, so the sibling-container
limitation above never applies to a normal local run:

```bash
# Just the Redis-backed Testcontainers tests, to confirm the setup works before a full build:
./gradlew :ipie-common-libs:test --tests '*Redis*'

# The Postgres/Elasticsearch-backed one:
./gradlew :ipie-service-template:test --tests 'UserControllerIntegrationTest'
```

The nesting limitation above is specific to running Gradle *itself* inside a container (e.g. a
`docker run eclipse-temurin:21-jdk ./gradlew ...` CI step, or any container-based CI runner) - it
is not a property of Docker Desktop/WSL2 or of these tests in general. If a build genuinely must
run Gradle inside a container, the closest workaround is mounting the host's Docker socket
(`-v /var/run/docker.sock:/var/run/docker.sock`) and setting `TESTCONTAINERS_RYUK_DISABLED=true`,
but confirm it actually reaches a started container's port in that specific environment before
relying on it - this does not work in every nested-Docker CI setup (see the note above).

**WSL-specific gotcha:** if the repository lives on a Windows drive mounted into WSL
(`/mnt/c/...`, `/mnt/d/...`), that mount is DrvFS, which doesn't support the file locking Gradle's
build cache relies on — a build run from WSL bash against a `/mnt/*` path can corrupt its own
`.gradle` cache mid-build (`Could not receive a message from the daemon`,
`Failed to release lock on execution history cache`). Two ways around it:

1. **Run the build from the Windows side** against the native path, via `gradlew.bat` (PowerShell
   or cmd, not WSL bash) — this is what verified Section 10's results:
   ```powershell
   powershell.exe -NoProfile -Command "cd D:\path\to\repo; $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; .\gradlew.bat build"
   ```
2. **Or** clone the repository onto the native Linux filesystem inside WSL (e.g. under `~/`, not
   `/mnt/*`) if you'd rather build from WSL bash directly.

```bash
# Whole platform, all static analysis + unit/architecture/integration tests:
./gradlew build

# Local stack (Postgres, RabbitMQ (Kafka commented out - see "Switching the event broker" below),
# Keycloak, Redis, Jaeger, Prometheus, Grafana, the service):
docker compose up --build

# Get a real access token (testuser has USER_READ/WRITE/DELETE - see deploy/keycloak/realm-export.json):
TOKEN=$(curl -s -X POST http://localhost:8080/realms/ipie/protocol/openid-connect/token \
  -d grant_type=password -d client_id=ipie-service-template \
  -d client_secret=ipie-service-template-secret \
  -d username=testuser -d password=testpass | python3 -c "import json,sys;print(json.load(sys.stdin)['access_token'])")

curl -X POST http://localhost:8091/api/v1/users \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"jdoe","email":"jdoe@example.com","fullName":"Jane Doe"}'

curl -H "Authorization: Bearer $TOKEN" http://localhost:8091/api/v1/users/{id}
curl http://localhost:8091/v3/api-docs
curl http://localhost:8091/actuator/health
```

Keycloak owns host port `8080` (matching `KC_HOSTNAME`, so the Account/Admin Console works
directly in a browser too, given a `127.0.0.1 keycloak` hosts-file entry - see
`Development_Environment_Configuration.md`'s "Testing the API with Postman" section);
`ipie-service-template` moved to `8091` to make room for it (see the docker-compose.yml comment on
why `8091`, not `8081` - a McAfee agent conflict on Windows hosts).

This is the sequence that verifies the repository — every piece
(PostgreSQL 18.4, Kafka 4.3.1, Keycloak 26.6.4, Zipkin - the tracing backend at the time; tracing
has since moved to Jaeger, see Section 12) was run for real (standalone rather than via Compose, to
verify each piece independently) and driven through the actual API: full CRUD + soft-delete +
idempotent retry, all three error shapes (401/403/404/409/400) with the documented common
`ApiError` body, a real Kafka consumer reading back the exact event the API produced, and Zipkin
showing the resulting trace.

`docker compose up --build` itself, and the Jaeger/Prometheus/Grafana observability stack
specifically, **have since been re-verified end to end** against RabbitMQ as the active broker
(see "Switching the event broker" below): the full stack was brought up via Compose, 20+
authenticated requests were driven through `/api/v1/users`, and confirmed live in all three tools
— `ipie-service-template` traces (with real span names like `authenticate bearertoken`,
`authorize request`, `http get /api/v1/users`) showing up in Jaeger's UI, the `ipie-service-template`
scrape target reporting `up` in Prometheus with `http_server_requests_seconds_count` matching the
request count driven, and the provisioned Grafana dashboard (see
`Development_Environment_Configuration.md`'s "Local Observability Stack Configuration") rendering
real numbers for that same traffic via its `prometheus`-uid datasource. This also caught and fixed
a real bug in the process: an uncommitted `common-observability/build.gradle` change (Brave/Zipkin
→ OTel/OTLP bridge) hadn't yet been picked up by the Docker image, so spans were silently going
nowhere until the image was rebuilt.

### Switching the event broker: Kafka ↔ RabbitMQ

The choice is made entirely by **which one config value is present** - no code changes, no
rebuild logic to touch. `EventPublisherConfig` and the two `*ConsumerConfig` classes pick the
broker in this order, first match wins:

| Priority | Property | Set to | Publisher/consumer activated |
|---|---|---|---|
| 1 | `spring.kafka.bootstrap-servers` | e.g. `kafka:9092` | `KafkaEventPublisher` + `UserEventLogConsumer` |
| 2 | `spring.rabbitmq.host` | e.g. `rabbitmq` | `RabbitEventPublisher` + `RabbitUserEventLogConsumer` |
| 3 | *(neither set)* | — | `LoggingEventPublisher` only (no consumer) |

**Never set both** - if you do, Kafka wins silently (`@ConditionalOnMissingBean` on the RabbitMQ
publisher bean) while `RabbitConsumerConfig`/`RabbitUserEventLogConsumer` would *still* activate
(they key off `spring.rabbitmq.host` independently), leaving a consumer listening on a queue
nothing publishes to. Pick one.

**In `docker-compose.yml`** (the primary way this stack is run) - **as of now, this file runs
RabbitMQ as the active broker, not Kafka**: the `kafka` service is commented out, the
`ipie-service-template` service's `environment` block sets `SPRING_RABBITMQ_HOST: rabbitmq` (with
`SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` commented out directly above it), and its
`depends_on` waits on `rabbitmq: condition: service_started` instead of `kafka`. `rabbitmq:4-management`
was already provisioned in this file with `RABBITMQ_DEFAULT_USER`/`PASS` both `guest` - Spring
Boot's own RabbitMQ defaults (port `5672`, user/pass `guest`/`guest`) match it exactly, so no other
property was needed to switch.

To switch back to Kafka:

1. In `docker-compose.yml`, uncomment the `kafka` service block.
2. In `ipie-service-template`'s `environment` block, uncomment `SPRING_KAFKA_BOOTSTRAP_SERVERS:
   kafka:9092` and comment out `SPRING_RABBITMQ_HOST: rabbitmq`.
3. In its `depends_on`, swap the `rabbitmq: condition: service_started` entry back to `kafka:
   condition: service_started`.
4. `docker compose up -d --build ipie-service-template kafka`.

Confirm which broker is actually active with `docker exec ipiemaster-ipie-service-template-1 env
| grep -i 'kafka\|rabbitmq'`, which should show only whichever one you intend to be active.

**Outside docker-compose** (bare `java -jar`, a different orchestrator, CI) - set the same two
Spring properties as environment variables or `-D` system properties: `SPRING_KAFKA_BOOTSTRAP_SERVERS`
/`IPIE_EVENTS_KAFKA_TOPIC` for Kafka, or `SPRING_RABBITMQ_HOST` (+ `SPRING_RABBITMQ_PORT`/`_USERNAME`/
`_PASSWORD` if not using RabbitMQ's own `5672`/`guest`/`guest` defaults) `/IPIE_EVENTS_RABBITMQ_EXCHANGE`
/`IPIE_EVENTS_RABBITMQ_QUEUE` for RabbitMQ - see `application.yml`'s `ipie.events.*` block for every
overridable property and its default.

**Verified live for both brokers**, not just unit-tested. RabbitMQ: pointed the running container
at it (env swap above), created a user through the real API, and confirmed the full chain -
`outbox_events` row written → `OutboxRelayScheduler` marked it published ~3s later →
`RabbitUserEventLogConsumer` logged the exact same event id and user id → `processed_events`
recorded the idempotency marker. Then reverted to the untouched `docker-compose.yml` and confirmed
Kafka reconnected and its consumer rejoined its group cleanly, with zero leftover RabbitMQ
activity either direction. Kafka: same check against the default (Kafka) stack - `outbox_events`
row published ~4s later, `UserEventLogConsumer` logged the same event id/user id,
`processed_events` recorded the marker - confirming the outbox → relay → broker → idempotent
consumer chain works identically regardless of which broker is configured.

**Important scope note on the above**: everything just described (the priority table, the
docker-compose switch steps, the live verification) is about the one *generic* consumer every
service ships - `UserEventLogConsumer`/`RabbitUserEventLogConsumer`, which just logs every event a
service publishes and (as of the audit-trail work, section 4) persists `AUDIT_EVENT` envelopes.
Flipping the broker switches that one consumer with zero code change, exactly as documented. It
does **not** by itself make every *other*, business-meaning-bearing consumer follow along - see
below.

### Adding a Kafka consumer for a cross-service business event

Every consumer that actually *does* something with a specific event (send an email, assign a
role, sync a projection - as opposed to `UserEventLogConsumer`'s generic "log everything") was,
for a long stretch of this platform's history, written against `@RabbitListener` only, with no
Kafka counterpart. That gap was real and is now closed for every consumer that existed when it was
found - `UserRegistrationCompletedEventConsumer`/`UserLoggedInEventConsumer`
(`ipie-communication-service`), `UserVerifiedEventConsumer`/`PillarResolutionEventConsumer`
(`ipie-iam-service`) - each now has a Kafka twin (`Kafka*EventConsumer`, see below for the naming
note). **This section is the recipe for the next one**, so a new cross-service consumer added
later doesn't quietly reintroduce the same gap.

**The one structural difference from RabbitMQ**: RabbitMQ gets a dedicated queue bound to just the
routing key(s) a consumer cares about (`UserServiceEventConsumerConfig`'s `Binding` beans) - the
broker itself does the filtering. Kafka has no equivalent - a publisher's `EventPublisherConfig`
puts *every* event type it publishes onto **one topic** (`ipie.events.kafka.topic`, e.g.
`ipie-user-service.events`), and `EventConsumerConfig`'s consumer factory always deserializes into
a raw `EventEnvelope<?>` (`JsonDeserializer.VALUE_DEFAULT_TYPE = EventEnvelope.class`, no way to
bind a specific payload type per listener the way Spring AMQP's converter can). So every Kafka
consumer subscribes to the *whole* topic and filters to the event type(s) it cares about itself,
inside the listener method - exactly the pattern `AuditEventCodec.decodeIfAuditEvent` already
established for recognizing `AUDIT_EVENT` envelopes out of that same generic stream.

**Steps, using `KafkaUserVerifiedEventConsumer` (`ipie-iam-service`) as the template:**

1. **New class, same package as the RabbitMQ consumer it complements**, named `Kafka<Event>Consumer`
   (see the naming note below for why the *existing* RabbitMQ classes are unprefixed instead of
   `Rabbit<Event>Consumer` - a historical inconsistency this doesn't try to retroactively fix).
2. `@Component @ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")` - the
   same class-level guard every Kafka listener in this platform uses, for the same reason
   `UserEventLogConsumer`'s Javadoc explains: Spring Boot's own Kafka auto-configuration supplies
   a default listener container factory the moment `spring-kafka` is on the classpath, regardless
   of whether this service is actually configured to use it.
3. **One `@KafkaListener` method**, subscribed to the *publisher's* topic - a new
   `ipie.integrations.<publisher>.kafka.topic` property (default matching that publisher's own
   `ipie.events.kafka.topic` default, e.g. `ipie-user-service.events`), **not** this service's own
   topic - and a `groupId` unique to this consumer (`${spring.application.name}.<event-name>`,
   e.g. `ipie-iam-service.user-verified`) so it gets its own full copy of the topic rather than
   competing with any other consumer group.
4. **Filter first, convert second**: `if (!"USER_VERIFIED".equals(event.eventType())) return;`,
   then `objectMapper.convertValue(event.data(), UserVerifiedEvent.class)` - `convertValue`
   (object-graph conversion), not `readValue` (JSON-string parsing), since `event.data()` at this
   generic boundary already arrived as a deserialized `LinkedHashMap`, not raw JSON text.
5. **Wrap the actual handling in `IdempotentEventHandler.handle(event.eventId(),
   processedEventStore, () -> ...)`**, identically to every other consumer - Kafka's own
   at-least-once delivery needs the same consumer-side idempotency guarantee RabbitMQ does.
6. **If the RabbitMQ consumer's logic is more than a couple of lines** (as
   `PillarResolutionEventConsumer`'s upsert/delete + manual audit recording was), **extract it
   into a shared, broker-independent class** (see `PillarResolutionEventHandler`) that both
   the RabbitMQ and Kafka consumer classes construct-inject and delegate to, rather than
   maintaining two copies of the same business logic that can silently drift apart. A
   one-or-two-line body (like `UserVerifiedEventConsumer`'s single `roleService.assignDefaultRole(...)`
   call) is fine duplicated as-is - not every shared line needs an abstraction.
7. **New Kafka topic-name property**, alongside the existing `ipie.integrations.<publisher>.rabbitmq.*`
   block in `application.yml` - see `ipie-iam-service`'s/`ipie-communication-service`'s
   `ipie.integrations.user-service.kafka.topic` for the pattern.
8. **Test it like `KafkaUserVerifiedEventConsumerTest`**: one case proving the matching event type
   is handled, one proving any other event type on the shared topic is silently ignored.

**Naming inconsistency, called out rather than silently left**: the platform-wide convention
(`UserEventLogConsumer` for Kafka, `RabbitUserEventLogConsumer` for RabbitMQ - unprefixed name is
Kafka, the historically-primary broker per this document's own priority order) is **not** what the
pre-existing cross-service consumers followed - `UserRegistrationCompletedEventConsumer`,
`UserVerifiedEventConsumer`, etc. were unprefixed *and* RabbitMQ-only, because they were written
back when only RabbitMQ was active in `docker-compose.yml` and nobody anticipated a second broker
needing the split. Renaming those existing, tested, referenced-elsewhere classes purely for naming
purity was judged not worth the churn - so their new Kafka twins are `Kafka`-prefixed instead of
the reverse. Follow whichever convention the sibling class you're extending already uses; don't
invent a third pattern.

### Switching the cache backend: Redis ↔ (future) another provider

Same "one config value decides, no code change" shape as the event broker above, except the
fallback is always safe rather than mutually exclusive - caching is an optimisation, not a
correctness requirement, so there is nothing equivalent to "never set both."
`IpieCacheAutoConfiguration` (`common-cache`) picks the `CacheManager`, first match wins:

| Priority | Condition | `CacheManager` activated |
|---|---|---|
| 1 | `spring-boot-starter-data-redis` on the classpath **and** `spring.data.redis.host` set | Redis-backed (Lettuce), JSON value serialization via the app's own `ObjectMapper` |
| 2 | either not true | `NoOpCacheManager` - every `@Cacheable` method still runs, just uncached |

**The Redis starter is opt-in per service, not bundled by `common-cache`.** `common-cache`
depends on `spring-boot-starter-data-redis` as `compileOnly` only - enough to compile the
Redis-specific configuration, not enough to put it on a consuming service's runtime classpath. A
service adds `spring-boot-starter-data-redis` itself if it wants Redis-backed caching (see
`ipie-service-template`'s `build.gradle`); a service that never adds it never gets
`RedisConnectionFactory` on its classpath at all. `IpieCacheAutoConfiguration.RedisCacheConfig` -
a nested, `@ConditionalOnClass(RedisConnectionFactory.class)`-guarded configuration, not a `@Bean`
method directly on the outer autoconfiguration class - is what makes that safe: `@Cacheable`'s
absence-of-Redis case can't fail classloading/verification of the outer autoconfiguration itself,
the same reasoning `common-resilience`'s `RestClientTimeoutConfiguration` uses for `RestClient`
(Section 4's build-system notes).

Application code (a `@Cacheable`/`@CacheEvict`/`@CachePut` annotation on an application-service
method, exactly like any other Spring app) never depends on Redis, Jedis, or any vendor type -
only on Spring's own cache abstraction - so every switch below is a dependency/configuration
change only, never an application-code change:

- **AWS ElastiCache for Redis / MemoryDB for Redis**: both speak the Redis protocol, so pointing
  `spring.data.redis.host`/`port`/`ssl.enabled` at the managed endpoint activates the same
  `redisCacheManager` bean unchanged - the same reasoning as `S3FileStorage` covering AWS S3 and
  MinIO through configuration alone.
- **Jedis instead of Lettuce**: `spring.data.redis.client-type=jedis` (+ `redis.clients:jedis` as
  a runtime dependency) is a Spring Boot autoconfiguration choice for `RedisConnectionFactory`
  itself - `RedisCacheConfig` only ever consumes whichever `RedisConnectionFactory` Boot hands it,
  so neither of its beans changes either way.
- **A cache that does not speak the Redis protocol at all**: a second `CacheManager`
  implementation, added the same way as `RedisCacheConfig` - its own nested,
  `@ConditionalOnClass`-guarded configuration with a `@Bean` guarded by its own
  `@ConditionalOnProperty`, ahead of or behind `redisCacheManager` in the precedence table above -
  not a rewrite of anything that calls it.

**In `docker-compose.yml`** - `ipie-service-template`'s `environment` block sets
`SPRING_DATA_REDIS_HOST: redis`, so the Redis-backed `CacheManager` is active by default in the
local stack; `management.health.redis.enabled: false` in `application.yml` keeps an
unreachable/misconfigured Redis from dragging `/actuator/health` down regardless (same reasoning
as the Kafka/RabbitMQ health indicators above - see that section's comment in `application.yml`).

**Verified against a real Redis** (Testcontainers, `common-cache`'s own
`IpieCacheAutoConfigurationTest`), not just by inspection: a value put through the Redis-backed
`CacheManager` round-trips correctly, a per-cache-name `ipie.cache.ttls.<name>` override actually
expires independently of the default TTL, and the no-op fallback never throws even though it never
stores anything.

---

## 12. Gaps, Open Decisions & Recommendations

What's missing, organized by how urgent it is.

### Must decide before any real environment goes live
1. **Identity provider — locally solved, not production-solved.** `docker-compose.yml` now runs
   a real Keycloak with a working realm/client/roles/permissions-claim mapping, verified
   end-to-end (Section 7). That realm (`deploy/keycloak/realm-export.json`) is a **local dev
   fixture** - two hardcoded test users, a client secret committed in plaintext, `KC_HOSTNAME`
   set for the compose network. None of that is appropriate for DEV/SIT/UAT/PROD (per
   `Development_Environment_Configuration.md`): those need their own realm(s) provisioned through
   whatever IaC/admin process the platform team chooses, real user federation, and the client
   secret coming from the Secrets Manager (that doc's section 4), not a checked-in JSON file.
2. ~~**Audit sink is still a logging stub.**~~ **Resolved** - `OutboxAuditRecorder` (durable,
   writes through the same transactional outbox as domain events) is what `ipie-service-template`
   actually runs on today, automatically preferred over `LoggingAuditRecorder` once a service has
   an `OutboxStore` bean (see Section 9) - `UserService` never had to change, since it only
   depends on the `AuditRecorder` port. `LoggingAuditRecorder` remains the reference fallback for
   a service with no `OutboxStore` bean yet, not the platform's only option.
3. **CI/CD pipeline definition.** The Gradle side (Section 10) is ready to be called from a
   pipeline, but no `Jenkinsfile` exists yet even though
   `Development_Environment_Configuration.md` section 16 names Jenkins specifically for this
   platform. Section 13 of the master standards doc lists the expected stages — none are
   automated yet.

4. **`IPIE_TRUSTED_PROXIES` is unset, so rate-limit buckets are shared.** Section 7 explains the
   mechanism. The empty default is correct for safety, but behind the API gateway every caller
   presents the gateway's address, which turns "5 registrations per minute per user" into "5 per
   minute for the whole platform". Set it to the ingress/gateway CIDR per environment. Services warn
   at startup while it is unset, so this is a release-checklist item, not a silent one.
5. **The rate limiter is in-memory unless Redis is wired.** `spring.data.redis.host` is unset by
   default, so `InMemoryRateLimiter` applies: N replicas mean N x the configured limit, and every
   deploy resets the buckets. Wherever the limit is meant to be a real control rather than a
   courtesy, Redis has to be configured — and it is worth confirming per environment rather than
   assuming, because nothing fails or logs when it is missing.
6. **Inter-service HMAC secrets have no default, by design — and nothing checks they are present
   until a call fails.** `IPIE_SECURITY_HMAC_KEY_USER_TO_IAM` and its siblings must be set on both
   the signing and verifying side. Unset, signing is still enabled, and every
   `ipie-user-service -> ipie-iam-service` call fails with `IllegalArgumentException: Empty key` and
   a `500` — which breaks registration completion, the only cross-service write path. This was found
   by the cross-service load test (Section 11), not by any unit or integration test, because every
   existing test either mocks the client or never crosses a service boundary.
7. **Peak-load behaviour of `InterServiceClient` is reasoned about, not measured.** The Bulkhead
   (10 concurrent calls per target, `max-wait-duration: 0`, so the 11th is rejected rather than
   queued), CircuitBreaker and Retry settings in `ipie-resilience-defaults.yml` are sensible
   defaults, not capacity decisions. `deploy/jmeter/ipie-registration-load-test.jmx` exercises the
   path, but the mail-delivery wait in each iteration keeps real concurrency at the crossing point
   too low to reach the bulkhead's limit. A plan that pre-creates confirmed registrations and then
   fires only the `complete` step is what would actually find the ceiling.

### Gaps found in the package layout and local stack
0a. **`ipie-config-service` isn't wired into the local stack yet.** It exists, compiles, and
    passes its own tests (Section 2), but has no `docker-compose.yml` entry and no Keycloak realm
    client — there's currently no way to run it as part of the local stack or authenticate against
    it.
0b. **`ipie-communication-service` has a `docker-compose.yml` entry but no Keycloak realm
    client.** It's reachable on host port `8094`, but token-based auth against it is unconfirmed —
    don't assume it works the same way `ipie-service-template`/`ipie-user-service`/
    `ipie-iam-service` do until it's checked directly.
0c. **OPA is provisioned in `docker-compose.yml` (port `8181`) but not called from any service
    code yet.** `common-libs`' `client` package has `OpaAuthorizationProperties` (config
    scaffolding only) — no service actually evaluates a policy against it. This is the same
    "provisioned, not consumed" gap — relevant
    directly to item 15 below (full OPA-based ABAC for file uploads): the OPA *server* exists now,
    the *integration* still doesn't.
0d. **`SERVICE_CLASS_REFERENCE.md` describes an older
    `api/application/domain/infrastructure` per-class layout** and needs correcting to the flat
    packages of Section 5.

### Should decide soon
4. **Dependency, secret and container scanning are not wired.** Nothing runs OWASP
   Dependency-Check/Snyk, gitleaks/trufflehog, or Trivy/Grype yet. These are normally cheap to
   add as Gradle plugins or pipeline steps once you've picked vendors.
5. **Versioning/release strategy — partly resolved.** `ipie-parent`,
   `ipie-common-libs` (with its test-fixtures variant) and `ipie-build-conventions` now publish to
   GitHub Packages, versioned from the single `version` property in `gradle.properties`, via the
   `publish` workflow on a `v*` tag. That was the prerequisite for repo-per-service: a service
   outside this repository can now resolve everything it needs instead of `project(':...')`.
   What remains open is the *policy* rather than the mechanism — semantic-version discipline
   (what constitutes a breaking change to `ipie-common-libs`), whether GitHub Packages stays the
   target or moves to the Cloud Artifact Repository named in
   `Development_Environment_Configuration.md` section 9, and how consumers are bumped. The
   intended answer to the last is an automated PR per service gated on that service's own CI,
   never a dynamic `1.+`/`-SNAPSHOT` range, which would make builds irreproducible and let one bad
   commit reach every service unchecked.
6. ~~**Sort-field validation.**~~ **Resolved** - `GET /api/v1/users?sortBy=...` now binds to
   `UserSortField`, an allow-list enum of the only actually-indexed sortable columns
   (`username`/`email`/`status`/`createdAt`); an invalid value is rejected by Spring's own enum
   conversion before it reaches JPA/Elasticsearch. This also surfaced (and fixed) a platform-wide
   gap: `GlobalExceptionHandler` had no `handleTypeMismatch` override, so any invalid enum query
   param (including the pre-existing `status` param) fell through to Spring's default
   `ProblemDetail` shape instead of this platform's `ApiError` - see Section 8's pagination
   subsection for the sortable-column rationale.
7. ~~**Resilience for outbound calls.**~~ **Resolved** - `common-resilience` now exists (Section
   2 above), shipping shared Retry (exponential backoff + jitter, allowlisted-exception-only)/
   CircuitBreaker/Bulkhead/TimeLimiter defaults plus a `RestClientCustomizer` for connection/
   response timeouts, matching `Development_Environment_Configuration.md` Section 15's Resilience
   control. `ipie-service-template` depends on it, but still has no outbound integration adapter
   of its own to demonstrate it against end-to-end - verified instead via
   `common-resilience`'s own test suite (`ResilienceDefaultsBehaviorTest`,
   `RestClientTimeoutCustomizerTest`), which proves each control (Timeout, Retry, Retry Backoff,
   Circuit Breaker, Bulkhead) through the real resilience4j-spring-boot3 AOP aspects with zero
   per-service YAML. Retry Safety and Fallback remain call-site responsibilities by nature (only
   the developer knows whether an operation is idempotent, or what a business-valid fallback
   looks like) - `common-resilience` cannot enforce either generically, only supply the mechanism.
8. ~~**Redis is provisioned, not consumed.**~~ **Resolved** - `common-cache`'s
   `IpieCacheAutoConfiguration` now auto-configures Spring's cache abstraction
   (`@Cacheable`/`@CacheEvict`/`@CachePut`) for every service that depends on it: a Redis-backed
   `CacheManager` when the service also adds `spring-boot-starter-data-redis` (opt-in - see
   "Switching the cache backend" under Section 11) and `spring.data.redis.host` is configured, a
   no-op one otherwise. Still pinned one major version behind latest on
   purpose: `docker-compose.yml` runs `redis:7-alpine` (BSD-3) rather than the current
   `8-alpine`, since Redis 8 relicensed to a choice of RSALv2/SSPLv1/AGPLv3 - staying on the last
   permissively-licensed major was the explicit call made here pending a compliance decision;
   revisit once legal/compliance signs off one way or the other.
9. ~~**No consumer-side Kafka example.**~~ **Resolved** - `UserEventLogConsumer`
   (`@KafkaListener`) and `RabbitUserEventLogConsumer` (`@RabbitListener`) both demonstrate
   `IdempotentEventHandler` + `ProcessedEventStore` end to end now (Section 9), and both the Kafka
   and RabbitMQ paths have been live-verified against a real broker (Section 11, "Switching the
   event broker") - nothing left open here.

### Worth knowing, low urgency
10. **Reusable `ipie-frontend-template` scaffold** — not built (`ipie-web` itself is a real, built
    app, not a reusable template a *new* frontend could copy the way a new backend microservice
    copies `ipie-service-template`; see Section 2's note on this distinction).
11. **Multi-arch / image tagging strategy** for the Dockerfile isn't defined (single-arch build
    only, `latest`-style tagging left to whatever CI pipeline is stood up).
12. **No dev/sit/uat-specific Spring profiles yet** — `application-prod.yml` exists (production
    overlay: `ipie.security.enabled=true` restated explicitly, real S3 addressing/SSE-KMS, lower
    trace sampling, INFO logging - see the file's own header for the secrets it still expects
    from outside the profile itself), alongside the base `application.yml` and the local-only
    `application-local.yml` override. What's still missing is separate DEV/SIT/UAT overlays
    (matching `Development_Environment_Configuration.md` section 13's tiers) - today, everything
    that isn't `local` runs on the same `prod` profile regardless of tier.
13. **`docker compose up --build` has not been run as a single, final end-to-end check.** Every
    piece was instead verified by running the real binaries standalone (JDK, Gradle, PostgreSQL,
    Kafka, Keycloak, Zipkin - the tracing backend at the time, since replaced by Jaeger and not
    re-verified - all run directly, not mocked) - see the verification notes throughout
    Sections 7, 9 and 11. Run `docker compose up --build` as the first check in your own
    environment, particularly to confirm the `KC_HOSTNAME` issuer-consistency fix (Section 7)
    behaves the same way inside an actual compose network as it did standalone.
14. **Kafka topic naming.** Kafka warns when a topic name mixes `.` and `_`
    (`ipie-service-template.events` is fine on its own, but be consistent platform-wide once more
    services publish topics - pick one separator convention now rather than after several
    services disagree).

15. **File-upload access control is permission-only, not full ABAC.** `DocumentPermissions`
    checks `DOCUMENT_READ`/`DOCUMENT_WRITE`, but nothing verifies the caller is actually
    associated with the `caseId` they're uploading to/downloading from (master standards doc,
    file-upload rules, section 5: OPA-based ABAC). `DocumentController`/`DocumentService` both
    flag this explicitly in their own Javadoc. Wiring real OPA policy evaluation is a substantial
    piece of its own - not built speculatively here.
16. **File-upload size limits are service-layer only, not gateway-level.** `FileSizeValidator`
    (backstop) and `spring.servlet.multipart.max-file-size` both exist, but master standards doc,
    file-upload rules, section 2 also calls for enforcement at the gateway (Kong/APISIX) - no
    gateway sits in front of this stack, so that layer doesn't exist yet.
17. **SSE-KMS has no real KMS key to point at locally.** `ipie.storage.s3.sse.kms-key-id` is
    unset by default - MinIO's local dev setup doesn't run a real KMS, so `S3FileStorage` falls
    back to SSE-S3 (AES256) when encryption is enabled without one. Set a real KMS key ARN in
    production alongside `sse.enabled=true`.
18. **Document retention periods are a mechanism, not a policy.** `documents.retention_until` and
    `DocumentService#retentionUntilFor` exist, but every doc type resolves to "no retention set"
    (`null`) today. IBC-related records almost certainly have statutory retention periods -
    master standards doc, file-upload rules, section 8 explicitly calls out needing
    legal/compliance confirmation before those are filled in; this template deliberately does not
    guess at them. No automatic purge/deletion job exists either, even once real periods are set.

---

### Third-party licences and paid tiers

Recorded because "is any of this licensed?" was asked directly, and because the answer is not
uniform: three of the components below are **copyleft (AGPLv3)** and one family is
**source-available, not open source**. None of it costs money as currently configured - but two
features this platform will almost certainly be required to turn on are paid.

**Nothing in the stack today requires a purchased licence key.** What follows is what changes that.

#### The Elastic family - the only paid-tier question in the stack

`elasticsearch`, `elasticsearch-logs`, `kibana` and `logstash` are all **8.18.8**, dual-licensed
**SSPL 1.0 / Elastic License 2.0**. ELv2 is *source-available*, not OSI-approved open source. Its
three restrictions are: no providing the software to third parties as a managed service, no
circumventing licence-key functionality, no removing licensing notices. None of them bind iPIE
running this for itself.

Worth being precise about the history: **the Elastic licence question predates the ELK work.** The
`elasticsearch` service backing the "search users" read path (`UserSearchIndexConfig`) has been on
8.18.8 since long before log aggregation existed. Adding Logstash and Kibana widened an existing
exposure; it did not create one.

Self-managed use runs on the **Basic** tier: free, no key, self-applying. Basic covers everything
this platform currently does - ingest, ILM/retention, Discover, dashboards, alerting rules,
snapshot/restore, TLS, and native/file-realm RBAC.

**Platinum (paid, annual subscription) is required for:**

| Feature | Why this platform will likely need it |
|---|---|
| **SSO into Kibana via OIDC/SAML** | The platform authenticates everything else through Keycloak. Basic gives Kibana Elasticsearch-native logins only - a second, separate credential set, which is exactly what an identity platform is not supposed to have. |
| **Elasticsearch audit logging** | Logs carry `userId` and `caseId`. Who read them is itself an auditable question under DPDP. |
| Field- and document-level security | Restricting which operators can see PII-bearing log fields, rather than all-or-nothing index access. |
| ML / anomaly detection, cross-cluster replication, searchable snapshots | Not needed now; listed so the tier boundary is not rediscovered later. |

Neither of the first two is wired, deliberately - see Section 9. They are a switch-on, not a
rebuild, but they are a **procurement decision that has not been made**. Since 8.16, Elasticsearch
and Kibana also offer **AGPLv3** as a third option (OSI-approved but copyleft); Logstash and the
Beats do not.

#### Copyleft components already in the stack

Not paid, but they carry obligations a permissive licence does not, and a government deliverable's
compliance review will ask:

| Component | Licence | Note |
|---|---|---|
| `grafana/grafana:11.4.0` | **AGPLv3** | Relicensed from Apache 2.0 in 2021. |
| `minio/minio` | **AGPLv3** | Relicensed from Apache 2.0 in 2021. Also `minio/mc`. |
| `clamav/clamav:1.5` | GPLv2 | |

Internal, unmodified use of all three is unproblematic; AGPL's network-distribution clause bites
only if a modified version is offered to third parties over a network. Flagged, not blocking.

#### Permissive - no action needed

`quay.io/keycloak/keycloak:26.6` (Apache 2.0), `prom/prometheus` (Apache 2.0),
`jaegertracing/all-in-one` (Apache 2.0), `otel/opentelemetry-collector-contrib` (Apache 2.0),
`openpolicyagent/opa` (Apache 2.0), `apache/kafka` (Apache 2.0), `rabbitmq` (MPL 2.0),
`mailhog/mailhog` (MIT), PostgreSQL (PostgreSQL Licence), `redis:7-alpine` (BSD-3).

`logstash-logback-encoder` - the library every service uses to emit JSON, and now also to ship it -
is Apache 2.0 / MIT dual. It is a *library*, unrelated to Logstash the daemon's licence.

#### The standing precedent

`redis:7-alpine` is pinned one major behind on purpose because Redis 8 relicensed to
RSALv2/SSPLv1/AGPLv3 - see Section 12, "Should decide soon". That decision established how this
platform treats a non-permissive licence: **stay on the permissive option and escalate, rather than
adopt and hope.** The Elastic Platinum question belongs to whoever owns that same escalation. It is
the same licence family and, unlike Redis, there is no permissively-licensed version to retreat to
if the answer is no - the fallback is a different product (Loki, or OpenSearch, which is Apache 2.0
end to end).

---

## 13. Binding Usage Rules — Use `ipie-common-libs`, Don't Rebuild It

Section 1 states the rule in prose; this section makes it checkable. Every row below is a
**binding requirement, not a style preference** — a service that rebuilds one of these locally
instead of using the listed common-lib mechanism is a standards violation, the same category as a
layering breach.

### 13.1 Use this, don't rebuild it

| Concern | Required mechanism | Do NOT |
|---|---|---|
| Business/domain exceptions (not-found, conflict, validation) | Extend `IpieException` (`common-core`) — `NotFoundException`/`ConflictException`/`ValidationFailedException`, or a new subclass of one of those with a service-specific `ErrorCode` | Throw raw `RuntimeException`/`IllegalStateException`, or build a second exception hierarchy |
| Database integrity violations (duplicate unique key, primary-key collision) | Declare the table's constraints with `IntegrityViolations` (`common-persistence`) in the repository that owns it, publish them as a bean so the boundary can read them too, and `throw VIOLATIONS.translate(e)` | Catch `DataIntegrityViolationException` and answer with one fixed message for every constraint - it will name the wrong field, and it will not run at all when Hibernate defers the insert to commit |
| Audit columns on any entity/model/projection (created/updated at & by, version) | Bundle into `AuditMetadata` (`common-core`) — one constructor parameter, not five | Take 5 loose audit fields as separate constructor params — this is the exact bug fixed in `UserDocument` (Section 10): a 13-parameter constructor tripping Checkstyle because the audit fields weren't bundled |
| Pagination (list/search endpoints) | `PageRequest`/`PageResult` (`common-core`, framework-agnostic) internally, `PageResponse` (`common-web`) at the API boundary | Hand-roll a paging DTO or expose Spring Data's `Page<T>` directly from a controller |
| API error response shape | `ApiError` + `GlobalExceptionHandler` (`common-web`) — add a new `@ExceptionHandler` method there for a new exception type | Define a second `@ControllerAdvice`/`@RestControllerAdvice`, or return an ad hoc error body — **mechanically enforced**, see 13.2 |
| JWT validation, current-user access, permission checks | `common-security`'s resource-server auto-configuration + `CurrentUserProvider`/`CurrentUser` + `PermissionEnforcer` | Parse/validate the JWT yourself, or compare role strings by hand instead of calling `PermissionEnforcer` |
| Correlation IDs / request tracing context, structured JSON logging | `CorrelationIdFilter` + `LoggingContext` + the shared logback JSON fragment (`common-observability`) | Write a second correlation-id servlet filter or a bespoke log format |
| Audit trail (who did what, for compliance) | `AuditRecorder` port + `@Auditable`/`AuditAspect` (`common-audit`) | Scatter `log.info("user X did Y")` calls through service code instead of going through the port |
| Storing a bearer secret (OTP code, verification token, credential-setup token) | `PepperedSecretHasher` when the secret is small enough to enumerate (a six-digit OTP), `DigestSecretHasher` when it is 256 bits from `SecretGenerator` (`common-security`) | Hand-roll `MessageDigest`/`Mac`, or pick the mode by how sensitive the secret feels - it is the **entropy of the input** that decides. Never either of these for a password: that is Argon2id, in ipie-iam-service |
| Transactional outbox and consumer idempotency **storage** | The platform's `JpaOutboxStore`/`JpaProcessedEventStore` (`common-events`) - declare an `@Entity` extending `AbstractOutboxEventJpaEntity`/`AbstractProcessedEventJpaEntity` for your table and pass it to the store | Write your own adapter for either port. Every service had a byte-identical copy, so a change to the port meant four edits - **mechanically enforced**, see 13.2 |
| Outbound domain events | `EventEnvelope` wrapped in `OutboxStore`/`OutboxRelay` (`common-events`) — business code writes to the outbox inside its own transaction, a scheduled relay hands it to `EventPublisher` | Call `EventPublisher.publish(...)` directly from business/application code (dual-write risk between the DB and the broker), publish a raw unwrapped payload, or invent a second envelope shape — **mechanically enforced**, see 13.2 |
| Inbound event consumption | `ProcessedEventStore` + `IdempotentEventHandler` (`common-events`) for dedup | Process an incoming event without idempotency handling |
| Integration tests against PostgreSQL | Extend `PostgresIntegrationTest` (`common-testing`) | Wire up your own Testcontainers `PostgreSQLContainer` per test class |
| Architecture/layering checks | Wire `LayeredArchitectureRules` (`common-testing`) into your own `ArchitectureTest`, per Section 5's `ArchitectureTest` | Skip architecture tests, or write bespoke layering checks that drift from the platform standard |
| Date/time formatting, string helpers, PII masking (PAN/Aadhaar/email), ID generation | `DateTimeUtils`/`Strings`/`DataMasking`/`IdGenerator` (`common-utils`) | Write ad hoc regex masking per service — this one is security-sensitive: passwords, tokens, Aadhaar/PAN and similar values must never appear unmasked in logs (Section 10's Guidelines subsection - DPDP Act, 2023 - is the actual legal basis for this, not just good practice) |
| Outbound call resilience (timeout/retry/circuit-breaker/bulkhead) | `@Retry`/`@CircuitBreaker`/`@Bulkhead`/`@TimeLimiter` + the shared defaults + `RestClientCustomizer` (`common-resilience`) | Hand-roll a retry loop, or add a per-service `resilience4j.*` YAML block for the common case |
| Caching (avoid repeating expensive/duplicate work) | `@Cacheable`/`@CacheEvict`/`@CachePut` — `common-cache`'s `IpieCacheAutoConfiguration` supplies the `CacheManager` (Redis-backed or no-op, Section 11's "Switching the cache backend") | Inject `RedisTemplate`/a raw Jedis client directly in application code, or hand-roll an in-memory `Map`-based cache (breaks the moment a service runs more than one instance) |
| Idempotency-key storage for `@Idempotent` endpoints | `IdempotencyStore` port + `IdempotencyAspect` (`common-web.idempotency`) — Redis-backed when `spring.data.redis.host` is configured, in-memory fallback otherwise; every service gets this automatically, no per-service table | Give each service its own `idempotency_keys` JPA entity/table (the original `ipie-user-service`/`ipie-iam-service` implementation, since replaced and removed — the DB-backed version had no TTL and grew without bound) |
| Rate limiting on public/unauthenticated endpoints | `RateLimitFilter` + `RateLimiter` port (`common-security.ratelimit`) — Redis-backed fixed-window counter when configured, in-memory fallback otherwise; a service registers the filter itself in its own `SecurityFilterChain` (see Section 5's `ArchitectureTest` note on `ResourceServerAutoConfiguration#configureBaseline`, the one supported way to add a filter on top of the platform baseline) only for the specific paths that need it | Hand-roll a request counter, or rely on Resilience4j's `@RateLimiter` for this (in-memory/per-instance only — does not correctly throttle across a horizontally scaled deployment) |
| Transport-level authentication between iPIE services (mutual TLS) | `ipie.client.security.mtls.*` (`common-client`) for the outbound half — it builds the `SslBundle` and attaches it to the `InterServiceClient` transport only — and Spring Boot's own `server.ssl.client-auth=need` for the inbound half. Off by default; enabling is a per-deployment decision that ships with the key material | Build a per-service `SSLContext`/`X509TrustManager`/keystore-loading helper, register a global `RestClientCustomizer` that swaps trust material for *every* HTTP client in the service (that also captures the Keycloak and pillar-IdP clients, which sit outside the platform's CA), or treat mTLS as making `hmac-signing-enabled` redundant — it does not, they authenticate different things and HMAC is the one that survives TLS termination at a proxy or mesh sidecar |

### 13.2 What's mechanically enforced today

Four of the layering rules from Section 5 already fail the build via ArchUnit
(`LayeredArchitectureRules`, wired into every service's own `ArchitectureTest` — see
`ipie-service-template`'s copy). Three more were added specifically to enforce rows from the table
above, because they're structurally checkable with low false-positive risk:

- **`exceptionsExtendIpieException`** — any class in the service's own base package whose name
  ends in `Exception` must be assignable to `IpieException`. Catches a hand-rolled exception
  hierarchy the day it's written, not at code review.
- **`noCompetingControllerAdvice`** — no class in the service's own base package may carry
  `@ControllerAdvice`/`@RestControllerAdvice`. Catches a second, competing error handler.
- **`applicationDoesNotCallEventPublisherDirectly`** — no class under `service`/`command`/`query`/
  `domain` may depend on `EventPublisher`. Catches a dual-write bug at compile/test time instead of
  in production the first time the broker is briefly unreachable — the outbox pattern (this table,
  "Outbound domain events") only holds if business code can *never* reach the publisher directly.

- **`noHandWrittenPlatformPortAdapters`** — no class in the service's own base package may
  implement `OutboxStore` or `ProcessedEventStore`, which the platform now implements once. A
  service that genuinely must replace one annotates the class `@PlatformOverride("reason")`, so the
  exception is deliberate and visible in review rather than silent. The rule exists because those
  adapters are the kind that get copied into every repository instead of shared.

The rest of the table (13.1) is **not** mechanically enforceable without real false-positive risk
(e.g. "did this audit event actually get published" is a semantic question, not a structural one)
— those rows are enforced through code review against this section, the same way they are today.
A new service created from `ipie-service-template` inherits all three new rules automatically,
since it copies `ArchitectureTest` unchanged (Section 5).

### 13.3 Adding something new to `ipie-common-libs`

If you hit a cross-cutting concern that isn't in the table above, the default is **not** to build
it locally in your service. Add it to the shared libraries instead, so the next service (and the
5 stakeholder-facing ones down the line) never rediscovers it:

1. **Decide it's actually cross-cutting first.** The bar: would a second, unrelated service need
   this too, purely because it's an iPIE service (not because it happens to share your domain)?
   Generic infra concerns (a new masking pattern, a new resilience policy, a new event-envelope
   field) qualify. Business logic specific to one domain (e.g. "how a user's status transitions")
   does not — that stays in the service.
2. **Place it in the right package** — extend an existing one (`utils` for a new masking pattern,
   `events` for a new envelope field) inside `ipie-common-libs` rather than creating a new one.
   `ipie-common-libs` is a single Gradle module (Section 2) organized
   by package, so "genuinely standalone" is not the bar for a new Gradle module (there's
   only one) — it's the bar for a new *package*, plus the dependency-direction rule
   `CommonLibsDependencyDirectionTest` enforces (nothing may depend "backward" into `core`). A
   package that pulls in a materially different set of dependencies than its neighbors (the way
   `resilience`/`utils` did when they were still separate modules) still deserves its own package
   and its own entry in the "`ipie-common-libs` packages" table (Section 2), just not a new
   `build.gradle`/`settings.gradle` entry — those now change only if the concern needs a dependency
   `ipie-common-libs/build.gradle` doesn't already declare.
3. **Test it in the library, not by proxy through a service.** `resilience`'s own test suite
   (Section 12, item 7) is the model: prove the mechanism works in the library that owns it, so
   `ipie-service-template` only needs to demonstrate it's wired in, not re-prove it works.
4. **Update this document in the same change** — add the row to 13.1 (and a new ArchUnit rule to
   13.2 if the concern is structurally checkable), so the binding rule and the capability ship
   together. A common-lib addition without a table row is exactly the gap that let `UserDocument`'s
   13-parameter constructor happen in the first place — the class existed to solve this, nobody
   was pointed at it.
5. **Versioning caveat** — this is still a monorepo where every service builds against
   `project(':ipie-common-libs')` directly, so a new common-lib capability is available to every
   service the moment it merges, with no publish/version-bump step. That remains true *inside* this
   repository. The artifacts also publish (Section 12, item 5), so a service
   building outside it pins a version instead — and adding something here then requires an explicit
   version bump before other services can pick it up. The two consumption modes coexist
   deliberately: in-repo modules keep the fast feedback of a project dependency, while an extracted
   service gets the isolation of a versioned one.

6. **If it is an adapter for a port that already lives here, it belongs here too.** Item 1 asks
   whether a concern is cross-cutting; this asks the same question one level down. A port in
   `ipie-common-libs` with exactly one correct implementation should ship that implementation, behind
   `@ConditionalOnMissingBean` where a service might reasonably differ. The test: *would every
   service write the same class to satisfy this interface?* If yes, writing it in the service is
   copying rather than implementing — and copies do not drift visibly, they drift silently. What
   stays in the service is only what genuinely varies: for the event stores, the table name and
   nothing else. The rule exists because `OutboxStore` and `ProcessedEventStore` are exactly the
   ports whose single implementation ends up kept in four places.

---

## 14. File Upload Standard (as implemented)

`common-file-storage`'s `filestorage` package (Section 2) plus the `Document` vertical slice
implement every one of the eight file-upload rule categories end to end - real code, not just
documentation, mirroring the same rigor as the messaging/outbox work in Section 9. **Confirmed
the `Document` slice is not template-only** — `ipie-config-service` carries an identical
copy (`Document`/`DocumentContext`/`DocumentFile`/`DocumentStatus`/`DocumentVersion` in `domain/`,
`DocumentJpaEntity`/`DocumentRepositoryImpl`/`DocumentPersistenceMapper` in `persistence/`,
`DocumentController` in `controller/`, migration `V6`) — `ipie-user-service`, `ipie-iam-service`,
and `ipie-communication-service` do not carry it.

### 14.1 The two ports, and how to swap what's behind them

Same pattern as `EventPublisher`: business code depends only on `FileStorage`/`VirusScanner`
(both in `common-file-storage`), never on a vendor SDK or ClamAV's protocol directly.

- **`FileStorage`** → `S3FileStorage` (`ipie-service-template`), built against the S3 API only
  (AWS SDK v2). Works unchanged against AWS S3, MinIO, or any other S3-API-compatible target -
  **config-only switch** (`ipie.storage.s3.endpoint`/`access-key`/`secret-key`/`region`), no new
  code needed to move from local MinIO to real AWS S3. If a backend that doesn't speak the S3 API
  at all is ever needed (e.g. Azure Blob's native SDK instead of its S3-compatibility gateway),
  that's a **new implementation** of `FileStorage`, selected the same way `EventPublisherConfig`
  picks Kafka vs. RabbitMQ - no caller of `FileStorage` changes either way.
- **`VirusScanner`** → `ClamAvVirusScanner` (real INSTREAM-protocol TCP client, self-hosted, no
  cloud/organizational approval needed) when `ipie.scanning.clamav.host` is configured, else
  `FailClosedVirusScanner` (rejects every upload - deliberately **not** "assume clean", unlike a
  missing event broker which safely degrades to logging). Swapping to a cloud-native scanner
  later (e.g. AWS malware protection, once approved) is a new `VirusScanner` implementation given
  precedence in `VirusScanConfig` the same way - see that class's Javadoc for the exact steps.

### 14.2 Mapped to each rule

| Rule | Implementation |
|---|---|
| 1. Type validation | `FileTypeValidator` (Apache Tika magic-byte sniffing, not extension/`Content-Type` header) against a caller-supplied whitelist - `DocumentTypeWhitelist` demonstrates a real *per-doc-type* whitelist (`court-order` → PDF only, `evidence` → PDF/JPG/PNG, etc.), never one global list. |
| 2. Size limits | `FileSizeValidator` (service-layer backstop) + `spring.servlet.multipart.max-file-size`. Gateway-level enforcement (Kong/APISIX) is **not** built - see Section 12, item 16. |
| 3. Security scanning | `ClamAvVirusScanner` + the quarantine-first flow in `DocumentService`: write to a `quarantine/` key first, scan, only `copy` to `permanent/` on a clean result; infected/unscannable content is deleted, never promoted. |
| 4. Storage & naming | `StorageKeyGenerator` - UUID-based keys structured `{module}/{caseId}/{docType}/{uuid}.{ext}`, never the user-supplied filename (kept as metadata only). `S3FileStorage` applies SSE (KMS when a real key is configured, else SSE-S3/AES256 - see Section 12, item 17) and runs over TLS via the AWS SDK by default. |
| 5. Access control | `DocumentPermissions` (`DOCUMENT_READ`/`DOCUMENT_WRITE`, via the existing `PermissionEnforcer`) + `FileStorage#presignedDownloadUrl` (short-lived signed URLs, never proxied raw storage access). Full OPA-based ABAC (case association, not just permission) is **not** built - see Section 12, item 15. |
| 6. Metadata & audit | The `documents` table (migration `V6`) captures uploader identity, timestamp, source module, SHA-256 hash (`FileHasher`) and original filename. Every upload/rejection is both `@Auditable`-recorded (or an explicit `AuditEventType.SECURITY` record for malware detections, since `@Auditable` only fires on success) and published through the same outbox → broker pipeline as `UserService`. |
| 7. Validation & UX | Typed exceptions (`UnsupportedFileTypeException`/`FileTooLargeException`/`MalwareDetectedException`/`ScanUnavailableException`, all real `IpieException` subtypes) give specific, actionable `ApiError` responses instead of one generic failure. Showing accepted formats/limits *before* upload in the UI is a frontend (`ipie-web`) concern, out of scope for this backend slice. |
| 8. Versioning & retention | Re-uploads are new `documents` rows linked via `supersedesId`/`versionNumber`, never an overwrite. `retention_until` is a real column, but every doc type resolves to "unset" today - see Section 12, item 18 for why (needs legal/compliance-confirmed statutory periods, not a guess). No automatic purge job exists either. |

### 14.3 Endpoints

All under `/api/v1/documents`, same `Authorization: Bearer <token>` requirement as the User API:

| | |
|---|---|
| `POST /api/v1/documents` | `multipart/form-data`: `file`, `caseId`, `docType`, optional `supersedesId`. Requires `DOCUMENT_WRITE`. `201` with the document metadata, or `400`/`422` per 14.2's validation table. |
| `GET /api/v1/documents/{id}` | Metadata only. Requires `DOCUMENT_READ`. |
| `GET /api/v1/documents?caseId=...` | All versions for a case, newest first. Requires `DOCUMENT_READ`. |
| `GET /api/v1/documents/{id}/download-url` | `{ "url": "...", "expiresInSeconds": 900 }` - a presigned URL, not a proxied download. Requires `DOCUMENT_READ`. |

### 14.4 Local dev stack

`docker-compose.yml` runs `minio` (S3 API on `9000`, console on `9001`, `minioadmin`/
`minioadmin`) + a one-shot `minio-init` that creates the `ipie-documents` bucket, and `clamav`
(INSTREAM protocol on `3310`, `clamdcheck.sh` healthcheck). `ipie-service-template` points at
both by default (`IPIE_STORAGE_S3_ENDPOINT`/`IPIE_SCANNING_CLAMAV_HOST` in its environment block)
- see `application.yml`'s `ipie.storage.s3.*`/`ipie.scanning.clamav.*` for every overridable
property and its default, and Section 11's "Switching the event broker" for the same style of
runbook applied to storage/scanning if you need to point at a different backend.

### 14.5 Verification status

**Live-verified end to end** against the real docker-compose stack, the same rigor as the
messaging/outbox work: uploaded a real file through `POST /api/v1/documents` with a genuine
Keycloak token, and confirmed every stage for real, not by inspection - `FileTypeValidator`
sniffed it as `application/pdf` from content, it landed in MinIO at the exact structured key
(`permanent/ipie-service-template/{caseId}/{docType}/{uuid}.pdf`) the response reported, the
`documents` row matched, the audit log recorded `DOCUMENT_UPLOADED`, the outbox → relay → Kafka →
`UserEventLogConsumer` chain fired for it (the same generic consumer already proven for `User`
events - confirming that pipeline is genuinely reusable, not User-specific), and the presigned
download URL returned the file byte-for-byte identical to what was uploaded.

That last step caught **two real bugs inspection alone had missed**, both fixed before this
document was written, not deferred:
1. `S3Presigner.Builder` has no `forcePathStyle()` shortcut the way `S3ClientBuilder` does -
   without explicitly setting the same `S3Configuration` on it, presigned URLs silently came back
   virtual-hosted-style (`https://bucket.host/...`), which doesn't resolve against MinIO at all.
2. Even after that fix, the URL embedded the docker-network hostname (`minio`) this service uses
   *internally* to reach the store - meaningless to an external caller. Fixed by adding
   `S3StorageProperties#publicEndpoint` (defaults to `endpoint` when unset, since a real AWS S3
   address is globally resolvable either way) and pointing the presigner at that instead.

**Not yet live-verified**: the `INFECTED` path specifically through the real HTTP API. It's
covered at two other levels instead - `DocumentServiceTest` proves the business logic (quarantine
deleted, nothing promoted, `MalwareDetectedException` thrown, a `SECURITY` audit event recorded)
against a mocked `VirusScanner`, and ClamAV's own ability to detect the standard EICAR test
signature is a well-established property of ClamAV itself. What hasn't been chained together yet
is uploading a real EICAR-containing file through the live API end to end - worth doing once a
whitelisted doc type that would actually accept a plain-text EICAR file (or an EICAR payload
wrapped in an accepted container format) is set up for the purpose.
