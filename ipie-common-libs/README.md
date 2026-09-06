# ipie-common-libs

Every iPIE shared library concern — exceptions/paging, web error handling, security, observability,
audit, events, resilience, inter-service client, general utils, file storage, cache, session — as
**one** Gradle module, organized by package instead of by module. A service takes exactly one
dependency on this instead of one per concern:

```groovy
implementation project(':ipie-common-libs')
```

Test fixtures (Testcontainers base classes, ArchUnit rules — formerly `common-testing`) live in a
separate `testFixtures` source set, so Testcontainers/`spring-boot-starter-test` never land on a
consuming service's main/runtime classpath:

```groovy
testImplementation testFixtures(project(':ipie-common-libs'))
```

Until 2026-07-20 these were 13 independent Gradle modules (`common-core`, `common-web`,
`common-security`, `common-observability`, `common-audit`, `common-events`, `common-resilience`,
`common-client`, `common-utils`, `common-file-storage`, `common-cache`, `common-session`,
`common-testing`). Package names are unchanged (`in.gov.ipie.common.<name>` per concern below), so
this was a move, not a rewrite — every class referenced here still lives at the same fully
qualified name it always did.

| Package | What it's for |
|---|---|
| [`core`](#core--framework-agnostic-building-blocks) | Base exceptions, paging primitives, audit metadata. Zero framework dependencies. |
| [`web`](#web--the-one-apierror-paging-shape) | The one API error/paging shape; `GlobalExceptionHandler`. |
| [`security`](#security--the-approved-jwtpermission-baseline) | JWT validation, permission checks, current-user context, Keycloak clients, HMAC request signing. |
| [`observability`](#observability--correlation-and-structured-logging) | Correlation id propagation, structured logging, tracing/metrics wiring. |
| [`audit`](#audit--the-businesssecurity-audit-trail) | The audit trail every auditable action goes through. |
| [`events`](#events--the-broker-agnostic-eventing-contract) | Event envelope, publisher port, transactional outbox, consumer idempotency. |
| [`resilience`](#resilience--timeouts-retry-circuit-breaking-and-rate-limiting-defaults) | Timeout/retry/circuit-breaker/bulkhead/rate-limiter defaults for outbound calls. |
| [`client`](#client--generic-secured-inter-service-http-client) | Generic, secured inter-service HTTP client. |
| [`utils`](#utils--small-dependency-free-helpers) | Small dependency-free helpers (date/time, text, masking, id gen, JSON, validation, collections, network, exceptions). |
| [`filestorage`](#filestorage--the-file-upload-contract) | File upload contract: storage, scanning, validation, naming, hashing. |
| [`cache`](#cache--spring-cache-abstraction-auto-configuration) | Auto-configures Spring's cache abstraction, Redis-backed or no-op. |
| [`session`](#session--idle-session-timeout-independent-of-jwt-expiry) | Idle-session timeout management, independent of JWT `exp`. |
| [`testing`](#testing-testfixtures--shared-test-infrastructure) | (`testFixtures`) Testcontainers base classes, shared ArchUnit rules. |

---

## `core` — framework-agnostic building blocks

Base exceptions and small shared domain primitives used across every iPIE service. Kept
intentionally small — business logic shared by only one or two services belongs in those
services, not here (master standards doc, 5.2). No Spring, no web, no persistence in this
package.

| Class | Why it exists |
|---|---|
| `correlation.CorrelationConstants` | The one place the correlation-id header name and MDC keys are defined, so `web` and `observability` agree on them without a package-level dependency. |
| `exception.CommonErrorCode` | Generic, ready-to-use error codes (`NOT_FOUND`, `CONFLICT`, ...) for services that don't need a more specific code. |
| `exception.ConflictException` | Base exception for state conflicts (duplicate unique key, stale optimistic lock); `web` maps it to HTTP 409. |
| `exception.ErrorCode` | The contract every stable, service-specific error code must implement. |
| `exception.FieldError` | One field-level validation failure, surfaced under an `ApiError`'s `fieldErrors`. |
| `exception.IpieException` | Base type for every business/domain exception in the platform — `web`'s `GlobalExceptionHandler` is written against this. |
| `exception.NotFoundException` | Raised when a requested resource doesn't exist; maps to HTTP 404. |
| `exception.ValidationFailedException` | Raised for business-rule validation beyond simple bean validation; maps to HTTP 400 with field errors. |
| `model.AuditMetadata` | The standard five audit columns (`created_at/by`, `updated_at/by`, `version`) as one value object. |
| `paging.PageRequest` | Framework-agnostic offset paging input. Use for small/admin screens needing page numbers and a total count; not for large/high-traffic listings (issues a `COUNT(*)`, gets slower the deeper you page). |
| `paging.PageResult` | Framework-agnostic offset-paged result, the counterpart to `PageRequest`. |
| `paging.Cursor` | Opaque keyset-pagination token (a `(createdAt, id)` tuple) backing `CursorPageRequest`/`CursorPageResult`. |
| `paging.CursorPageRequest` | Framework-agnostic keyset ("seek") paging input, the scalable counterpart to `PageRequest` — no `COUNT(*)`, stays fast at any depth. |
| `paging.CursorPageResult` | Framework-agnostic keyset-paged result. Carries no `totalElements`/`totalPages` on purpose — `hasMore()` is all a caller needs. |

## `persistence` — the shared entity base, and constraint translation

`AuditableJpaEntity` plus the translation of database integrity violations into the error each one
actually means. JPA itself is `compileOnly` here, so a service with no JPA on its classpath still
compiles against this module.

A repository declares the constraints of the table it owns, and publishes the declaration as a bean:

```java
static final IntegrityViolations VIOLATIONS = IntegrityViolations.forTable()
        .primaryKey("users_pkey")
        .conflict("uq_users_email", "A user with this email address already exists")
        .conflict("uq_users_phone_number", "A user with this phone number already exists")
        .build();
...
} catch (DataIntegrityViolationException e) {
    throw VIOLATIONS.translate(e);
}
```

**Publishing it as a bean is not optional.** Hibernate often defers the insert to flush, and with the
transaction committing after the repository method returns, the violation is raised from the commit -
outside that `catch` entirely. `GlobalExceptionHandler` translates those from the registered
declarations, so a declaration kept private is consulted by nothing but the `catch` beside it.

| Class | Why it exists |
|---|---|
| `AuditableJpaEntity` | The shared audit + soft-delete columns every JPA entity inherits (`createdAt/createdBy/updatedAt/updatedBy/version/isActive/deletedAt/deletedBy`). |
| `IntegrityViolations` | Reads the failing constraint's name from Hibernate's `ConstraintViolationException` and reports the conflict that actually occurred, instead of one fixed message for every constraint. A constraint the table did not declare is rethrown rather than dressed as a 409 - a foreign key or check failure is a bug to see, not something the caller can fix. |
| `IdCollisionException` | A generated id that duplicated an existing row. Deliberately not a `ConflictException`: a conflict will fail again, this would succeed with a fresh id. Retrying is not done for you - by the time it surfaces, the entity already holds the generated id, so only the caller can mint a new one. |

## `web` — the one API error/paging shape

Common API error model, global exception handling and web/paging standards shared by every iPIE
service. Controllers must rely on this instead of building their own error responses (master
standards doc, 5.3/5.4).

| Class | Why it exists |
|---|---|
| `config.WebErrorAutoConfiguration` | Registers `GlobalExceptionHandler` for every service via Spring Boot auto-configuration. |
| `error.ApiError` | The one error response JSON shape every iPIE API returns — no service invents its own. |
| `error.GlobalExceptionHandler` | Translates exceptions (domain, validation, security, unexpected) into `ApiError`. Also overrides `handleTypeMismatch` (bad enum query/path param) and logs `utils.exception.ExceptionUtils.getRootCause` alongside the full trace on the unexpected-error catch-all, since the caught exception is often just a generic wrapper. |
| `paging.PageResponse` | The common JSON shape for an offset-paged API response — controllers return this, never a raw `List`. |
| `paging.CursorPageResponse` | The common JSON shape for a keyset-paged API response, the counterpart to `PageResponse`. |

## `security` — the approved JWT/permission baseline

JWT validation, permission-based authorization and current-user context shared by every iPIE
service (master standards doc, 5.5). Services must not implement their own token validation.

| Class | Why it exists |
|---|---|
| `config.IpieSecurityProperties` | `ipie.security.*` settings: the permissions JWT claim, public paths, allowed CORS origins. |
| `config.JwtPermissionsConverter` | Turns the configured permissions claim on a validated JWT into `PERMISSION_*` Spring Security authorities. |
| `config.ResourceServerAutoConfiguration` | The approved security baseline: JWT validation, permission-authority mapping, CORS, and a `ipie.security.enabled=false` local-dev escape hatch. |
| `context.CurrentUser` / `CurrentUserProvider` / `SecurityContextCurrentUserProvider` | The authenticated caller for the current request, resolved via a port rather than touching `SecurityContextHolder` directly. |
| `permission.DefaultPermissionEnforcer` / `PermissionAuthorities` / `PermissionEnforcer` | Programmatic permission checks (`require(permission)`), honouring the local-dev escape hatch uniformly. |
| `keycloak.KeycloakTokenClient` | Calls Keycloak's token endpoint with the `refresh_token` grant to mint a fresh access token — see "Refreshing a token" below. |
| `keycloak.RefreshTokenContextHolder` / `RefreshTokenCaptureFilter` | Carries the current request's refresh token so `client.TokenRelayInterceptor` can use it — not auto-registered, read the holder's Javadoc first. |
| `keycloak.admin.KeycloakAdminClient` | Creates a new Keycloak client via the Admin REST API — platform-provisioning, not business-microservice. See "Provisioning a new client" below. |
| `hmac.HmacSignature` | The one canonicalization + HMAC-SHA256 computation both `client.HmacSigningInterceptor` and `HmacSignatureVerificationFilter` use. |
| `hmac.HmacSigningProperties` | `ipie.security.hmac.*` — shared signing keys, clock-skew tolerance, nonce TTL, protected paths. |
| `hmac.NonceStore` / `InMemoryNonceStore` / `RedisNonceStore` | Replay protection — Redis-backed when configured, in-memory fallback otherwise. |
| `hmac.HmacSignatureVerificationFilter` | Verifies signature/timestamp/nonce on protected paths — not auto-registered. See "Signing high-sensitivity calls" below. |

### Refreshing a token

`client`'s `TOKEN_RELAY` security mode forwards the inbound caller's own JWT unchanged — but if
that JWT has already expired by the time an outbound call is made, forwarding it verbatim just
makes the downstream call fail too. `KeycloakTokenClient` closes that gap:

```yaml
ipie:
  security:
    keycloak:
      token-uri: http://keycloak:8080/realms/ipie/protocol/openid-connect/token
      client-id: ipie-service-template
      client-secret: ${IPIE_SECURITY_KEYCLOAK_CLIENT_SECRET:}
```

Once configured, `TokenRelayInterceptor` automatically checks the current JWT's `exp` claim and,
only if it has passed **and** a refresh token is available (via `RefreshTokenContextHolder`),
calls `KeycloakTokenClient.refreshAccessToken(refreshToken)` and relays the fresh access token
instead. Without a refresh token available, an expired token is still forwarded unchanged.

Getting a refresh token into `RefreshTokenContextHolder` in the first place is deliberately not
automatic: wire `RefreshTokenCaptureFilter` in yourself only once you've decided the calling
client may supply one via `X-Refresh-Token` — **read that class's Javadoc first**, forwarding a
refresh token beyond its original client is a real trade-off. Most inter-service calls should
prefer `client`'s default `CLIENT_CREDENTIALS` mode instead, which never touches a refresh token.

Keycloak rotates the refresh token on every use — `KeycloakTokenResponse.refreshToken()` is the
*new* one; whatever originally supplied the refresh token is responsible for storing it for the
next refresh.

### Provisioning a new client

`KeycloakAdminClient.createClient(clientId, serviceAccountsEnabled)` automates the "register a
Keycloak client for the service" step otherwise done by hand-editing
`deploy/keycloak/realm-export.json`. It is **off by default**:

```yaml
ipie:
  security:
    keycloak:
      admin:
        enabled: true   # deliberately not implied by setting the properties below
        base-url: http://keycloak:8080
        realm: ipie
        admin-username: ${IPIE_KEYCLOAK_ADMIN_USERNAME}
        admin-password: ${IPIE_KEYCLOAK_ADMIN_PASSWORD}
```

**Read `KeycloakAdminClient`'s Javadoc before enabling this anywhere.** It needs real Keycloak
*admin* credentials — a platform-provisioning capability, not something a typical business
microservice should carry in its normal runtime configuration.

### Signing high-sensitivity calls

Defense in depth for genuinely high-sensitivity regulatory actions (e.g. CIRP/Liquidation/PGIRP-
style state transitions) — on top of, never instead of, the platform's standard transport/token
security. Two independent halves, both opt-in:

**Receiving side** — list which paths actually require a valid signature, and the shared key(s):

```yaml
ipie:
  security:
    hmac:
      keys:
        claims-service-key: ${IPIE_HMAC_CLAIMS_SERVICE_KEY:}
      clock-skew-tolerance: 5m
      nonce-ttl: 10m
      protected-paths:
        - /api/v1/cirp/**
```

Then wire `HmacSignatureVerificationFilter` into your own `SecurityFilterChain` (not
auto-registered). `NonceStore` is already auto-configured — inject it, don't construct it.

**Calling side** — `client`'s `HmacSigningInterceptor`, layered on top of whichever
`ipie.client.security.mode` is already selected (see the `client` section below):

```yaml
ipie:
  client:
    security:
      hmac-signing-enabled: true
      hmac-signing-key-id: claims-service-key
```

Both sides read the *same* `ipie.security.hmac.keys` map — a key id is a secret shared between
exactly the services that sign/verify calls under it. A request to a protected path missing a
valid signature, carrying an expired timestamp, or replaying an already-consumed nonce is
rejected with `401` before it reaches the controller.

## `security.secret` — storing a bearer secret

Two services needed this and each answered separately and correctly, with the reasoning recorded
nowhere. It is here now, with the rule on the port: **the entropy of the input decides the mode, not
how sensitive the secret feels.**

| Class | Use it when |
|---|---|
| `PepperedSecretHasher` | The secret is small enough to enumerate - a six-digit OTP has a search space of one million, so an unkeyed digest of it is reversible by anyone holding the database. Construction fails without a pepper rather than silently falling back. |
| `DigestSecretHasher` | The secret came from `SecretGenerator` - 256 bits of randomness, no dictionary to run, so a pepper adds a key to manage for no gain. |
| `SecretGenerator` | Issuing the token in the first place: 32 bytes of `SecureRandom`, URL-safe Base64 because it travels in a link. Not a UUID - a v7 id encodes its creation time, which narrows a guess from a neighbouring token. |

**Neither is a password hasher.** A password is human-chosen, reused, and must be slow to verify -
Argon2id, in ipie-iam-service, which owns credentials. These are fast on purpose.

The digests are already in production databases, so the encoding (UTF-8 in, lower-case hex out) is
fixed. `SecretHasherCompatibilityTest` holds it still with known-answer vectors computed
independently of this code: change it and nothing errors, every secret issued before the deploy
simply stops matching.

## `observability` — correlation and structured logging

Correlation id propagation, structured logging fields and tracing/metrics wiring shared by every
iPIE service (master standards doc, 5.6).

| Class | Why it exists |
|---|---|
| `config.ObservabilityAutoConfiguration` | Registers the correlation-id servlet filter for every consuming service. |
| `correlation.CorrelationIdFilter` | Reads/generates `X-Correlation-Id`, publishes it to the MDC, echoes it back — sanitizes the inbound value first (CRLF/header-injection risk otherwise). |
| `correlation.LoggingContext` | Sets structured MDC fields (case id, user id, correlation id) instead of calling `MDC` directly. |

## `audit` — the business/security audit trail

Technical, security and business audit support shared by every iPIE service (master standards
doc, 5.7). Business modules must not bypass this mechanism for auditable actions (section 16).

| Class | Why it exists |
|---|---|
| `AuditRecorder` | Port every service writes audit records through. `outbox.OutboxAuditRecorder` (durable) is used automatically once a service has an `OutboxStore` bean; `LoggingAuditRecorder` is the fallback otherwise. |
| `outbox.OutboxAuditRecorder` | Durable `AuditRecorder`: writes through the same transactional outbox `events` defines for domain events. |
| `LoggingAuditRecorder` | Reference `AuditRecorder`: writes each event as one structured JSON line under a dedicated `AUDIT` logger. |
| `annotation.Auditable` | Marks an application-service method as an auditable business action; `AuditAspect` turns each successful call into one `AuditEvent`. |
| `aspect.AuditAspect` | Turns a `@Auditable` method call into an `AuditEvent` after successful completion, and swallows any audit-recording failure rather than failing the business operation. |
| `config.AuditAutoConfiguration` | Wires the `AuditRecorder` precedence chain and `AuditAspect` via auto-configuration. |
| `model.AuditEvent` | A single audit record shape: who, what, when, from where, which service/entity/case, old/new values. |
| `model.AuditEventType` | The two audit categories (`SECURITY`, `BUSINESS`) — separate from ordinary application logs (`observability`'s concern). |

## `events` — the broker-agnostic eventing contract

Common event envelope, publisher contract and consumer-side idempotency support (master standards
doc, section 9). Deliberately messaging-technology-agnostic.

| Class | Why it exists |
|---|---|
| `envelope.EventEnvelope` | The one envelope shape every business event is published in, carrying a separate *event contract* version from the code version. |
| `idempotency.IdempotentEventHandler` | Wraps event handling in the required check-then-mark idempotency pattern. |
| `idempotency.ProcessedEventStore` | Port for tracking which inbound event ids a consumer has already handled — each service backs this with its own storage. |
| `outbox.OutboxRelay` | Drains an `OutboxStore` into an `EventPublisher`, one batch at a time — pure orchestration, framework/persistence-agnostic. |
| `outbox.OutboxStore` | Port for the transactional outbox pattern: `save` must run inside the same DB transaction as the business change. |
| `publisher.EventPublisher` | The port application services publish events through, silent on which broker is behind it. Only ever called by `OutboxRelayScheduler`, never business code directly. |

## `events.jpa` — the storage half of the outbox, held once

The ports above are broker-agnostic on purpose, and for a while that was read as "the bindings live
in the service". In practice each port had exactly one implementation, copied into every service:
`JpaOutboxStore`, `JpaProcessedEventStore`, their Spring Data repositories, the row shapes and the
relay scheduler came to about 440 lines that were byte-identical in four repositories. A change to a
port meant four edits, and adding `deletePublishedBefore` once left a test fake in this module
uncompilable until someone noticed.

A service now declares where its rows live and nothing else:

```java
@Entity
@Table(name = "outbox_events")          // "iam_outbox_events" under the shared database
class OutboxEventEntity extends AbstractOutboxEventJpaEntity { }

@Bean
OutboxStore outboxStore(EntityManager em, ObjectMapper mapper) {
    return new JpaOutboxStore<>(em, mapper, OutboxEventEntity.class, OutboxEventEntity::new);
}
```

The entity type is passed in rather than discovered, and its constructor as a method reference
rather than by reflection, so an entity that cannot be constructed fails the compile instead of the
first event published. `OutboxRelayAutoConfiguration` then runs the relay for any service that has
both a store and a publisher — `@EnableScheduling` on the application class is all that is needed.

**A service must not write its own adapter for either port.** That is not advice: it fails the build
through `LayeredArchitectureRules.noHandWrittenPlatformPortAdapters`, which every service inherits
with its `ArchitectureTest`. A service that genuinely has to replace one annotates it
`@PlatformOverride("reason")`, so the exception is deliberate and visible in review.

| Class | Why it exists |
|---|---|
| `AbstractOutboxEventJpaEntity` / `AbstractProcessedEventJpaEntity` | The row shapes, so only the table name stays with the service. |
| `JpaOutboxStore` / `JpaProcessedEventStore` | The one JPA implementation of each port, proven against a real PostgreSQL in this module's own tests. |

## `resilience` — timeouts, retry, circuit-breaking and rate-limiting defaults

Timeout, retry (exponential backoff + jitter), circuit breaker, bulkhead, rate limiter and
idempotency-aligned retry-safety defaults for every outbound/inter-service call
(`Development_Environment_Configuration.md`, Section 15). A new microservice only needs the
merged dependency — defaults apply automatically to any `@CircuitBreaker`/`@Retry`/`@Bulkhead`/
`@TimeLimiter`/`@RateLimiter` instance name with no `resilience4j.*.instances.<name>` override.

| Class | Why it exists |
|---|---|
| `config.IpieResilienceAutoConfiguration` | Applies the shared defaults and an explicit `RestClient` connect/response timeout to every consuming service. |
| `config.IpieResilienceHttpProperties` | `ipie.resilience.http.*` — connection/response timeout for the auto-configured `RestClient`. |
| `config.YamlPropertySourceFactory` | Lets `@PropertySource` load the resilience defaults from a non-`application.yml`-named YAML file. |
| `exception.TransientDependencyException` | Marks an outbound call failure as safe-to-retry (timeouts, connection resets, 5xx). Only genuinely transient failures should use this. |

### Customizing the defaults

Every value ships as a Spring property with a sane default; a consuming service overrides only
the knob it actually needs.

**HTTP connect/response timeout** (`ipie.resilience.http.*`): defaults `connect-timeout: 2s` /
`response-timeout: 5s`.

```yaml
ipie:
  resilience:
    http:
      response-timeout: 10s   # this service's downstream calls are known to be slower
```

**Retry / CircuitBreaker / Bulkhead / TimeLimiter / RateLimiter** (`resilience4j.*`, shipped as a
`configs.default` block in `ipie-resilience-defaults.yml`, loaded at the *lowest* precedence — a
consuming service's own `application.yml` always wins):

- **Override one instance name** — `resilience4j.<type>.instances.<name>.*`; every other instance
  name keeps inheriting `configs.default` untouched (resilience4j's own fallback mechanism).
- **Override the shared default itself** — redefine `resilience4j.<type>.configs.default.*`.

**Rate limiting** is self-throttling to contain blast radius — complements, does not replace,
gateway/mesh-level rate limiting. Default 20 calls/second per instance name, fail-fast (a
rejected call throws `RequestNotPermitted`, deliberately not retried).

## `client` — generic, secured inter-service HTTP client

A service that needs to call another microservice depends on this and passes a target service
name + `ServiceRequest` — it does not hand-roll a `RestClient`, wire its own resilience
annotations, or figure out how to attach an `Authorization` header itself. One generic
`InterServiceClient.exchange(...)`/`execute(...)` pair covers every HTTP method/body shape.

| Concern | How | Source package |
|---|---|---|
| Connect/response timeout | Already-applied `RestClientCustomizer` | `resilience` |
| Retry / CircuitBreaker / Bulkhead / RateLimiter | One instance per target service name, inheriting `resilience`'s shared `configs.default` | `resilience`'s registries |
| Security (`Authorization` header) | Keycloak OAuth2 client-credentials by default; token relay opt-in | `client` + `security` |
| Correlation id | Forwards the current request's correlation id from MDC | `CorrelationPropagationInterceptor` |
| Idempotency-Key | Required at `ServiceRequest` build time for `POST`/`PUT`/`PATCH`/`DELETE` | `ServiceRequest.Builder` |
| Audit trail | One `AuditRecorder` event per call | `audit` |

```java
@Service
class ClaimsLookupService {

    private final InterServiceClient interServiceClient;

    ClaimsLookupService(InterServiceClient interServiceClient) {
        this.interServiceClient = interServiceClient;
    }

    ClaimView findClaim(String claimId) {
        return interServiceClient.exchange(
                ServiceRequest.get("claims-service", "/api/v1/claims/" + claimId).build(),
                ClaimView.class);
    }
}
```

`ServiceRequest` also has `put`/`patch`/`delete`/`of(serviceName, method, path)` factories, plus
`.queryParam(name, value)` and `.header(name, value)` on the builder.

### Configuring where a service name resolves to

No service discovery/registry in the platform yet — target resolution is config-driven:

```yaml
ipie:
  client:
    base-url-pattern: http://{service}:8080
    services:
      claims-service: https://claims.internal:8443
```

### Configuring security

Default is `CLIENT_CREDENTIALS` (this service authenticates as itself):

```yaml
ipie:
  client:
    security:
      mode: CLIENT_CREDENTIALS   # default
      registration-id: ipie-interservice   # default

spring:
  security:
    oauth2:
      client:
        registration:
          ipie-interservice:
            provider: keycloak
            client-id: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_CLIENT_ID:ipie-service-template}
            client-secret: ${IPIE_INTERSERVICE_CLIENT_SECRET:ipie-service-template-secret}
            authorization-grant-type: client_credentials
        provider:
          keycloak:
            token-uri: ${IPIE_KEYCLOAK_TOKEN_URI:http://keycloak:8080/realms/ipie/protocol/openid-connect/token}
```

If `CLIENT_CREDENTIALS` (the default) is selected with no OAuth2 client registration configured,
the application still starts — the failure surfaces loudly, but only the first time a
`CLIENT_CREDENTIALS`-mode call is actually attempted (an `IllegalStateException` naming exactly
which `registration-id` is missing).

Other modes: `TOKEN_RELAY` (forwards the inbound caller's own JWT — see `security`'s "Refreshing
a token" for handling expiry) and `NONE` (no `Authorization` header, logs a startup warning).

**HMAC request signing** is additive on top of whichever mode is selected:

```yaml
ipie:
  client:
    security:
      hmac-signing-enabled: true
      hmac-signing-key-id: claims-service-key   # must match security's ipie.security.hmac.keys
```

### Service-to-service authorization (OPA)

Additive, opt-in, composed *outermost* of everything above:

```yaml
ipie:
  client:
    security:
      opa:
        enabled: true
        url: http://opa:8181
        policy-path: /v1/data/ipie/interservice/allow
        fail-closed: true   # default - an unreachable OPA denies the call, never silently allows it
```

Queries OPA's Data API with `{"input": {"caller": ..., "target": ..., "method": ..., "path": ...}}`.
See `deploy/opa/policies/interservice.rego` for a sample policy.

### Inter-service mTLS

**Off by default.** Nothing changes for a service that does not set `enabled: true`, and no key
material needs to exist anywhere until one does.

```yaml
ipie:
  client:
    security:
      mtls:
        enabled: true
        key-store: file:/etc/ipie/certs/user-service.p12   # this service's own identity - required
        key-store-password: ${IPIE_MTLS_KEY_STORE_PASSWORD}
        key-store-type: PKCS12                             # default
        key-alias: user-service                            # optional, but see below
        trust-store: file:/etc/ipie/certs/internal-ca.p12   # the CA that issues peer certs
        trust-store-password: ${IPIE_MTLS_TRUST_STORE_PASSWORD}
        include-system-ca-certificates: false              # default - internal CA only
        enabled-protocols: [TLSv1.3, TLSv1.2]              # default
```

Enable it together with an `https://` target, since mTLS only happens on a TLS handshake:

```yaml
ipie:
  client:
    base-url-pattern: https://{service}:8443
```

The **inbound** half is Spring Boot's own — this library deliberately does not wrap it:

```yaml
server:
  ssl:
    enabled: true
    bundle: ipie-server                # or key-store/key-store-password directly
    client-auth: need                  # `need` rejects a caller with no certificate; `want` does not
    trust-store: file:/etc/ipie/certs/internal-ca.p12
    trust-store-password: ${IPIE_MTLS_TRUST_STORE_PASSWORD}
```

`client-auth: want` is worth avoiding: it requests a certificate and accepts the connection anyway
if none arrives, so the control appears to be on while authenticating nothing.

**Misconfiguration fails at startup, not at the first call.** A missing keystore, a wrong password,
a wrong store type or a `key-alias` that isn't in the store all raise an `IllegalStateException`
naming the property responsible. That is deliberate: the alternative failure mode — an opaque TLS
handshake error on a downstream call, hours after the deploy — is much harder to trace back. A
trust store with no keystore is rejected outright rather than quietly degrading to one-way TLS.

**Scope.** The bundle is attached to the `InterServiceClient` transport only, not registered as a
global `RestClientCustomizer`. A global one would also replace the trust material used by
`security`'s `KeycloakTokenClient`/`KeycloakAdminClient` and by ipie-user-service's pillar IdP
back-channel clients, which talk to identity providers outside the platform's own CA — they would
break the moment mTLS was switched on, for reasons unrelated to inter-service traffic.
`include-system-ca-certificates: true` merges the JVM's default trust anchors back in for the
transitional case where the same trust material has to reach a public endpoint too.

**mTLS does not replace HMAC signing.** They authenticate different things:

| | What it proves | Where it stops |
|---|---|---|
| mTLS (`mtls.*`) | The process at the other end of *this socket* holds the private key for a certificate the trust store accepts. Protects the whole conversation. | At the first TLS termination point — a load balancer, an ingress, a mesh sidecar. Says nothing about any individual message. |
| HMAC signing (`hmac-signing-enabled`) | *This message* — method, path, timestamp, nonce, body — was produced by a holder of the shared key and has not been altered. Adds replay protection and a per-request artefact an auditor can re-verify. | Nowhere in the request path; the signature travels with the message. |

So the Section 15 signing requirement for CIRP/Liquidation/PGIRP-style state transitions stands
unchanged with mTLS on. What mTLS does allow is keeping HMAC signing confined to those paths,
rather than reaching for it as a general-purpose "prove the caller is a service" mechanism.

**Relationship to the service mesh.** `Infra_Environment_Configuration.md`, Section 12 records a
service mesh as the destination for mTLS, and that has not changed — a mesh also brings certificate
rotation and SPIFFE workload identity, which a library cannot. But a mesh is a Kubernetes
capability and the platform runs on Docker Compose, where every hop is plain `http://` today. This
is the interim application-layer answer, shaped to be thrown away: migrating to a mesh means
setting `enabled: false` and unmounting the keystores. No call site, `ServiceRequest`, or business
code ever sees any of it.

### Audit trail

Every call through `InterServiceClient` — success or failure — is recorded as one `AuditEvent`
(`AuditEventType.SECURITY`, action `INTER_SERVICE_CALL`) through `audit`'s `AuditRecorder`.

### Exception model

- **Timeout, connection failure, or `5xx`** → `TransientDependencyException` (`resilience`) —
  retried per the shared Retry/CircuitBreaker allowlist.
- **A `4xx` response** → `RemoteServiceException` — deliberately not retried, not an
  `IpieException` subtype. Catch it at the call site and translate to a domain-appropriate
  exception; left uncaught, surfaces as a generic 500 via `web`'s `GlobalExceptionHandler`.
- **Circuit open / bulkhead full** → resilience4j's own exceptions propagate unwrapped. No
  fabricated fallback ships here — that's a call-site decision.

| Class | Why it exists |
|---|---|
| `InterServiceClient` / `DefaultInterServiceClient` | The generic port and its `RestClient`-based implementation. |
| `request.ServiceRequest` | One outbound call's method/path/query/headers/body/idempotency-key. |
| `config.ResilienceRegistries` | Groups the four resilience4j registries into one parameter (Checkstyle parameter-count limit). |
| `config.InterServiceClientProperties` / `InterServiceSecurityProperties` / `InterServiceMtlsProperties` | `ipie.client.*` config. |
| `config.InterServiceClientAutoConfiguration` | Wires the `InterServiceClient` bean, correlation interceptor, OAuth2 client-credentials manager, and the opt-in mTLS request factory. |
| `security.InterServiceMtlsSslBundles` | Turns `ipie.client.security.mtls.*` into an `SslBundle`, failing at startup on anything that would silently degrade the control. |
| `security.OAuth2ClientCredentialsInterceptor` / `TokenRelayInterceptor` / `HmacSigningInterceptor` | The three security add-ons above. |
| `correlation.CorrelationPropagationInterceptor` | Carries the current request's correlation id onto the outbound call. |
| `exception.RemoteServiceException` | A downstream `4xx` — see "Exception model" above. |

## `utils` — small dependency-free helpers

General-purpose helpers (date/time, text, masking, id generation, JSON, validation, collections,
network, exceptions). Intentionally no Spring/web/persistence dependency — trivially reusable
anywhere, including batch jobs and CLI tooling. Jackson (`json.JsonUtils`) is the one dependency
exception.

| Class | Why it exists |
|---|---|
| `datetime.DateTimeUtils` | ISO 8601 date/time helpers. |
| `id.IdGenerator` | Id and human-readable business-reference-number generation. |
| `masking.DataMasking` | Masks sensitive values (email, PAN, Aadhaar, generic) before logging/display. |
| `text.Strings` | Null/blank-safe string helpers. |
| `json.JsonUtils` | Jackson (de)serialization helpers outside the Spring MVC request/response cycle. |
| `validation.ValidationUtils` | Format validators for India-specific identifiers and simple shapes — pairs with `masking.DataMasking`. |
| `collection.CollectionUtils` | Null-safe collection helpers (empty checks, chunking/partitioning). |
| `network.NetworkUtils` | Dependency-free IP helpers (`X-Forwarded-For` parsing, IPv4/IPv6 validation, log-masking) — plain strings, not a servlet request. |
| `exception.ExceptionUtils` | Generic `Throwable` inspection — root cause, stack trace to string, typed cause lookup. First real caller: `web.GlobalExceptionHandler.handleUnexpected`. |

**Deliberately elsewhere, not folded into `utils`:** paging (`core`/`web` — a web/API concern),
file hashing/storage-key generation (`filestorage` — file-domain-specific), request
signing/JWT/Keycloak (`security` — already owns that domain).

## `filestorage` — the file-upload contract

Storage, scanning, validation, naming, hashing shared by every service that accepts file uploads.
Deliberately silent on which object store or scanner is behind it, the same way `events` is
silent on the broker.

| Class | Why it exists |
|---|---|
| `storage.FileStorage` | The port every service uploads/downloads through. |
| `scanning.VirusScanner` | The port every uploaded file is scanned through before leaving quarantine. No "unconfigured = assume clean" implementation ships here on purpose. |
| `scanning.ScanResult` / `ScanStatus` | The outcome of one scan pass — `ERROR` is never treated as clean. |
| `validation.AllowedFileType` | One entry in a per-use-case whitelist. No global default whitelist ships here. |
| `validation.FileTypeValidator` | Validates a file's *actual* sniffed content (Tika magic-byte detection), never extension/`Content-Type`. |
| `validation.FileSizeValidator` | Enforces a per-file size limit — a service-layer backstop. |
| `naming.StorageKeyGenerator` | Builds UUID-based storage keys, never the user-supplied filename. |
| `hash.FileHasher` | SHA-256 hashing for dedup/integrity metadata. |
| `exception.UnsupportedFileTypeException` / `FileTooLargeException` / `MalwareDetectedException` / `ScanUnavailableException` | Typed failures the upload pipeline can produce. |
| `exception.FileStorageErrorCode` | Stable error codes for the file-upload pipeline, implementing `core`'s `ErrorCode`. |

## `cache` — Spring cache abstraction auto-configuration

Auto-configures `@Cacheable`/`@CacheEvict`/`@CachePut` with zero per-service YAML: Redis-backed
when a service also adds `spring-boot-starter-data-redis` and `spring.data.redis.host` is
configured, a no-op `CacheManager` otherwise.

```groovy
// Opt-in, only if you want Redis-backed caching:
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

| Class | Why it exists |
|---|---|
| `config.IpieCacheAutoConfiguration` | Picks the `CacheManager`: Redis-backed (Lettuce, JSON via the app's own `ObjectMapper`) when Redis is on the classpath and configured, `NoOpCacheManager` otherwise. |
| `config.IpieCacheProperties` | `ipie.cache.ttls.<name>` per-cache-name TTL overrides. |

### Switching the cache backend

Application code only ever depends on Spring's cache abstraction, never Redis/Jedis/any vendor
type. AWS ElastiCache/MemoryDB: point `spring.data.redis.host`/`port`/`ssl.enabled` at the managed
endpoint. Jedis instead of Lettuce: `spring.data.redis.client-type=jedis`.

## `session` — idle-session timeout, independent of JWT expiry

A logged-in user is warned before, and can choose to extend past, a configurable idle limit — a
UX/compliance control layered on top of, not instead of, the token's own technical expiry. **On
by default** (`ipie.session.enabled=true`).

| Concern | How |
|---|---|
| Per-request activity tracking | `SessionActivityFilter` refreshes the idle window on every authenticated request. |
| `GET /api/v1/session/status` | `{active, remainingSeconds, warningThresholdSeconds}`. |
| `POST /api/v1/session/extend` | Resets the idle window to `ipie.session.extend-by`. |
| `POST /api/v1/session/logout` | Explicit logout. |

```yaml
ipie:
  session:
    enabled: true                # default
    idle-timeout: 15m            # default
    warning-before-timeout: 1m   # default
    extend-by: 15m               # default - falls back to idle-timeout if unset
```

`SessionStore` (Redis-backed when configured, in-memory single-JVM fallback otherwise) holds one
expiry timestamp per user id.

| Class | Why it exists |
|---|---|
| `config.SessionProperties` | `ipie.session.*` config. |
| `config.SessionAutoConfiguration` | Wires the `SessionStore` precedence, `SessionService`, `SessionActivityFilter`, `SessionController`. |
| `SessionService` | The idle-session business logic: `touch`/`status`/`extend`/`logout`. |
| `SessionStatus` | The frontend-facing snapshot. |
| `store.SessionStore` / `InMemorySessionStore` / `RedisSessionStore` | Port backing one user's session expiry. |
| `web.SessionActivityFilter` | Touches the caller's session on every authenticated request, at a low priority so it runs after Spring Security's chain. |
| `web.SessionController` | The `/api/v1/session/*` REST surface every service gets automatically. |

## `testing` (`testFixtures`) — shared test infrastructure

Testcontainers PostgreSQL/Elasticsearch base classes and the common ArchUnit layering rules
(master standards doc, section 10.1/16), so every service enforces the same architecture
boundaries. Lives under `src/testFixtures`, not `src/main` — consumed via
`testImplementation testFixtures(project(':ipie-common-libs'))`, never a service's main/runtime
classpath.

| Class | Why it exists |
|---|---|
| `archunit.LayeredArchitectureRules` | The layering rules every service's own `ArchitectureTest` applies against its own base package. |
| `containers.ElasticsearchIntegrationTest` | Testcontainers mixin for a real, version-pinned Elasticsearch instance. An interface, so a test needing both this and `PostgresIntegrationTest` can implement both. Pins `ES_JAVA_OPTS` to a 512m heap: a container with no memory limit sizes its heap from the whole host, and is OOMKilled before it logs `started` - which the test reports as a wait-strategy timeout, reading like a slow start rather than a memory ceiling. |
| `containers.PostgresIntegrationTest` | Testcontainers mixin for a real PostgreSQL instance, shared across the JVM for speed. |
| `containers.RedisIntegrationTest` | Testcontainers mixin for a real Redis instance (`redis:7-alpine`, matching `docker-compose.yml`). |

## Full docs

See `SERVICE_CLASS_REFERENCE.md` and `MASTER_CODE_STANDARDS.md`.
