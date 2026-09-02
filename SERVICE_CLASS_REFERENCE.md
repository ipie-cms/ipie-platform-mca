# iPIE Service & Class Reference

This document is the single place that explains **why each shared class exists** — its purpose and
role, not what its methods do line by line (the code itself is the source of truth for that).

**Scope: the skeleton and the shared library only** — `ipie-service-template` and
`ipie-common-libs`. It is the companion to `MASTER_CODE_STANDARDS.md`, which covers architecture,
build system and cross-cutting standards and links here rather than repeating per-class rationale.
Together those two answer "how do I build a service".

**It deliberately does not document the deployable services.** What each microservice owns and its
API endpoints are in `ARCHITECTURE_WORKING_PLAN.md` §4.0/§4.0.1; the classes inside a given service are in
that service's own `docs/LLD_*.docx`. Adding a service should require no change here.

*Migrated 2026-08-13 from the retired `ipieMaster` monorepo, where it had been stranded while all
five copies of `MASTER_CODE_STANDARDS.md` still linked to it. Refreshed against the tree at the same
time — see "Packages added since the first pass".*

Organized by module (`ipie-service-template`, `ipie-common-libs`), then by package within it,
following each service's own layering. `ipie-common-libs` was 13 independent Gradle modules until
2026-07-20; the section headings below still group by former-module name (`common-core`,
`common-web`, ...) since that's still the package name (`in.gov.ipie.common.core`,
`in.gov.ipie.common.web`, ...) - see `ipie-common-libs/README.md` for the merge itself.

---

## 1. `ipie-service-template`

The approved starting point for every iPIE backend microservice, carrying a full working
vertical slice (User CRUD) so the pattern is visible end to end.

### Where each `ipie-common-libs` package is demonstrated

Eight packages already have real, working usage in this service's own User/Document flow - that
real usage *is* the best example, so no separate demo code exists for them. The four packages
with no natural call site yet in this template's own business logic (`common-utils`, `common-cache`,
`common-resilience`, `common-client`) instead have small, explicitly-not-production `*Example`
classes under `examples/` (package `in.gov.ipie.service.template.examples`) - each one's own test
proves it actually runs.

Since 2026-08-31 **every** module has an `*Example` class, not only those four. The ten added later
are a per-module quick reference: shorter to read than tracing a capability through `UserService`
and `DocumentService`, and the first place to look when adopting a module in a new service. They do
not replace the real call sites in the table below, which remain the authoritative demonstration of
how a capability is used in anger.

| Package | Look here | Notes |
|---|---|---|
| `common-core` | `domain/exception/*`, `UserService`/`DocumentService` (`PageResult`/`PageRequest`) | Base exceptions and paging primitives, used throughout. |
| `common-web` | Any controller response - `GlobalExceptionHandler`/`ApiError` apply automatically, never called directly | The one error shape, applied platform-wide. |
| `common-security` | `UserPermissions`/`DocumentPermissions` + `UserController`/`DocumentController` (`@PreAuthorize`), `DocumentService` (`CurrentUserProvider`) | Permission checks and current-user context. |
| `common-observability` | `UserService.enqueueEvent`/`DocumentService.enqueueEvent` (`LoggingContext.correlationId()`) | Correlation id threaded into every published event. |
| `common-audit` | `UserService`/`DocumentService` (`@Auditable`), `DocumentService.recordSecurityAudit` (manual `AuditEvent` for a path `@Auditable` can't cover - see its Javadoc) | Both the annotation-based and manual recording paths. |
| `common-events` | `UserService`/`DocumentService` (`OutboxStore.save`), `messaging/**` (the two entity subclasses and `EventStoreConfig`, the Kafka/RabbitMQ bindings, the consumers) | The full outbox -> relay -> broker -> consumer pipeline. The stores and the relay itself are the platform's since 2026-08-18; the service declares only which tables its rows live in. |
| `common-file-storage` | `DocumentService.uploadDocument` (the whole quarantine -> scan -> promote pipeline), `infrastructure/storage/S3FileStorage`, `infrastructure/scanning/ClamAvVirusScanner` | Every port (`FileStorage`, `VirusScanner`, validators, `StorageKeyGenerator`, `FileHasher`) in one real flow. |
| `common-testing` | `UserControllerIntegrationTest` (`PostgresIntegrationTest`/`ElasticsearchIntegrationTest`), `ArchitectureTest` (`LayeredArchitectureRules`) | Testcontainers mixins and the shared ArchUnit rules. |
| `common-utils` | `examples/CommonUtilsExample` | `DataMasking`, `IdGenerator`, `DateTimeUtils`, `Strings`, `JsonUtils`, `ValidationUtils`, `CollectionUtils`, `NetworkUtils`, `ExceptionUtils`. |
| `common-cache` | `examples/CommonCacheExample` | `@Cacheable`/`@CachePut`/`@CacheEvict` - the underlying Redis-backed `CacheManager` is proven separately by `common-cache`'s own `IpieCacheAutoConfigurationTest`. |
| `common-resilience` | `examples/CommonResilienceExample` | `@Retry`/`@CircuitBreaker` - the shared defaults themselves are proven separately by `common-resilience`'s own `ResilienceDefaultsBehaviorTest`. |
| `common-client` | `examples/CommonClientExample` | `InterServiceClient`/`ServiceRequest` calling a hypothetical `notification-service` - not part of this stack; see the class's Javadoc. |

### Root

| Class | Why it exists |
|---|---|
| `ServiceTemplateApplication` | The Spring Boot entry point. Marks the one thing a new service must rename on day one (class, package, Gradle module, `spring.application.name`) — everything else in the template is copied unchanged. Also carries `@EnableScheduling`, which exists solely to drive `OutboxRelayScheduler`, not any business-specific job. |

### `api` — HTTP layer only

| Class | Why it exists |
|---|---|
| `UserPermissions` | Centralizes the User API's permission-name constants (`USER_READ`/`USER_WRITE`/`USER_DELETE`) so business code checks permissions, never hard-coded role names. |
| `controller.UserController` | The User CRUD REST endpoints. Deliberately thin: HTTP mapping, status codes, idempotency-key lookup, and permission checks only — all business rules live in `UserService`. Ships two list endpoints: `GET /users` (offset paging, `PageResponse`, has a total count) and `GET /users/cursor` (keyset paging, `CursorPageResponse`, no count but stays fast at any depth) - pick per listing based on scale/UI needs, per `common-core`'s `PageRequest`/`CursorPageRequest` Javadoc. |
| `mapper.UserApiMapper` | MapStruct interface converting between API request/response DTOs and the application/domain model, so the domain model never needs to know about JSON shapes. |
| `request.CreateUserRequest` | Validated inbound shape for `POST /users` — bean-validation annotations live here, not on the domain model. |
| `request.UpdateUserRequest` | Validated inbound shape for `PUT /users/{id}`. |
| `response.UserResponse` | The API's outbound User shape — decoupled from both the JPA entity and the domain model so persistence or domain changes don't automatically change the public contract. |
| `DocumentPermissions` | Centralizes the Document API's permission-name constants (`DOCUMENT_READ`/`DOCUMENT_WRITE`). Documents the known ABAC gap in its own Javadoc - this is a permission check, not a case-association check. |
| `DocumentTypeWhitelist` | Per-`docType` file-type whitelists (master standards doc, file-upload rules, section 1) - deliberately service-specific, not something `common-file-storage` defines. |
| `controller.DocumentController` | The Document upload/download REST endpoints - multipart handling, permission checks, and mapping only; the whole quarantine/scan/promote pipeline lives in `DocumentService`. |
| `mapper.DocumentApiMapper` | Hand-written domain -&gt; response mapping (no request-DTO side exists, since uploads arrive as multipart fields, not JSON) - deliberately never exposes `storageKey`. |
| `response.DocumentResponse` | The API's outbound Document shape - never includes the storage key; callers get a presigned URL from a separate endpoint instead. |

### `application` — use-case orchestration

| Class | Why it exists |
|---|---|
| `command.CreateUserCommand` | Application-layer input for "create a user," shaped by the use case rather than the HTTP request. |
| `command.UpdateUserCommand` | Application-layer input for "update a user." |
| `command.UploadDocumentCommand` | Application-layer input for "upload a document" - carries the whole file as `byte[]` (not a stream) so the same bytes can be validated, hashed, scanned and stored without re-reading. Defensively copies the array in and out (unlike a typical record accessor), since unlike this record's other fields a `byte[]` is mutable. |
| `service.UserService` | Where the User business rules actually live: uniqueness checks, state-transition rules, `@Auditable` recording, domain event publishing, transaction boundaries. Controllers call this; this never calls back up to `api`. |
| `service.DocumentService` | Orchestrates the full quarantine-first upload pipeline (master standards doc, file-upload rules): validate -&gt; quarantine -&gt; scan -&gt; promote/reject -&gt; persist -&gt; audit -&gt; outbox event. The only class that calls `FileStorage`/`VirusScanner` on the business side. |
| `service.DocumentUploadProperties` | Groups `DocumentService`'s two configuration values (as opposed to its six collaborator beans) into one constructor parameter, the same reasoning as `AuditMetadata` bundling the audit columns - keeps the constructor at 7 parameters instead of 8. |

### `domain` — business rules, no framework dependencies

| Class | Why it exists |
|---|---|
| `event.UserEventType` | The stable names + contract version for the business events this service publishes (`USER_CREATED`, etc.) — consumers version against this, not against Kafka topic internals. |
| `event.DocumentEventType` | Same pattern as `UserEventType`, for `DOCUMENT_UPLOADED`/`DOCUMENT_UPLOAD_REJECTED`. |
| `exception.EmailAlreadyExistsException` | Raised when a create/update would violate the unique-email rule; maps to HTTP 409 via `ConflictException`. |
| `exception.UserErrorCode` | Stable, service-specific error codes returned to API callers — a contract that must not change meaning once released. |
| `exception.UserNotFoundException` | Raised when a lookup by id fails; maps to HTTP 404 via `NotFoundException`. |
| `exception.UsernameAlreadyExistsException` | Raised when a create would violate the unique-username rule; maps to HTTP 409. |
| `exception.DocumentErrorCode` | Stable error codes for the Document domain, same reasoning as `UserErrorCode`. |
| `exception.DocumentNotFoundException` | Raised when a lookup by id fails, or when `supersedesId`/a download target points at a document that doesn't exist; maps to HTTP 404. |
| `model.User` | The domain model — deliberately independent of the JPA entity so persistence details never leak into business rules. Carries Lombok's `@Getter` (generating the same JavaBean getters MapStruct needs to map it automatically, see `UserApiMapper`) — this class is also the platform's one live Lombok+MapStruct combination, and what `MASTER_CODE_STANDARDS.md` section 5's Lombok convention tests `lombok-mapstruct-binding` against. |
| `model.UserSortField` | Allow-list enum of the only columns `GET /api/v1/users` may sort by (`USERNAME`/`EMAIL`/`STATUS`/`CREATED_AT`) — each backed by a real index. Bound directly as `UserController`'s `sortBy` `@RequestParam` type, so Spring's own enum conversion rejects anything else before it reaches JPA/Elasticsearch. |
| `model.UserSearchCriteria` | Optional filter set for listing/searching users; framework-agnostic so both the JPA and Elasticsearch search adapters can consume it. |
| `model.UserStatus` | A user's visibility state (`ACTIVE`/`INACTIVE`). Exists because rows are never hard-deleted — deletion is a status flip. |
| `model.Document` | The domain model for document metadata - never the file bytes, only a pointer (`storageKey`) into `FileStorage`. |
| `model.DocumentStatus` | Final upload outcome (`CLEAN`/`INFECTED`) - no "pending" state, since scanning happens synchronously before any row is ever persisted. |
| `model.DocumentContext` | Where a document belongs (case + doc type) - one of three value objects `Document`'s fields are grouped into, the same way `AuditMetadata` groups the audit columns, to keep the constructor at 7 parameters instead of 13. |
| `model.DocumentFile` | What the uploaded file actually is (filename, storage key, content type, size, hash) - the second of the three grouped value objects. |
| `model.DocumentVersion` | A document's re-upload version counter (number + `supersedesId`) - the third grouped value object. |
| `repository.UserRepository` | The domain-owned **port** for User persistence — pure business vocabulary (`save`, `findById`, `existsByUsername`, ...), no JPA/SQL knowledge. `UserService` depends only on this. |
| `repository.UserSearchIndex` | A second, deliberately separate port for the User *search read model* (vs. `UserRepository`'s *write model*) — lets a service run with Postgres-only search when no Elasticsearch cluster is available, without `UserService` knowing which is active. |
| `repository.DocumentRepository` | The domain-owned port for Document metadata persistence, same reasoning as `UserRepository`. |

### `infrastructure` — everything technology-specific

| Class | Why it exists |
|---|---|
| `configuration.JpaAuditingConfig` | Wires JPA's `created_by`/`updated_by` audit columns to the authenticated caller (via common-security), so entities never set these fields themselves. |
| `configuration.OpenApiConfig` | Publishes OpenAPI docs — mandatory for every iPIE API. |
| `idempotency.IdempotencyKeyEntity` | Row shape for one already-handled `Idempotency-Key`. Deliberately package-private and not mapped to any domain model — this is pure infrastructure plumbing, not a business concept. |
| `idempotency.IdempotencyKeyRepository` | Spring Data JPA interface backing `IdempotencyKeyEntity` storage. |
| `idempotency.IdempotencyService` | Implements the check-then-store idempotency pattern behind the `Idempotency-Key` header, so a retried `POST` returns the original response instead of creating a duplicate. |
| `messaging.EventConsumerConfig` | Wires the `@KafkaListener` container factory for `messaging.consumer.UserEventLogConsumer`, only when Kafka is configured — the consumer-side counterpart to `EventPublisherConfig`, kept equally un-nested for the same reason. |
| `messaging.EventPublisherConfig` | Chooses which `EventPublisher` bean is active (Kafka, RabbitMQ, or the logging fallback, in that precedence) based on which broker is configured — the one class that wires `messaging/publisher`'s three adapters together, which is why it stays un-nested at the top of `messaging/`. |
| `messaging.RabbitConsumerConfig` | Declares the RabbitMQ topic exchange/queue/binding and the `@RabbitListener` container factory for `messaging.consumer.RabbitUserEventLogConsumer`, only when RabbitMQ is configured — the RabbitMQ sibling of `EventConsumerConfig`, standby in case Kafka doesn't get organizational clearance. |
| `messaging.consumer.JpaProcessedEventStore` | JPA-backed `ProcessedEventStore` (common-events) — the reference implementation of consumer-side idempotency, same reasoning as `IdempotencyService` on the synchronous API side. Shared by both the Kafka and RabbitMQ consumers, since idempotency is broker-agnostic. |
| `messaging.ProcessedEventEntity` | Where this service records the event ids it has already acted on, extending the platform's `AbstractProcessedEventEntity`. Same arrangement as the outbox entity. |
| `messaging.consumer.RabbitUserEventLogConsumer` | RabbitMQ counterpart to `UserEventLogConsumer` — same `IdempotentEventHandler` demonstration, active only when RabbitMQ is the configured broker instead of Kafka. |
| `messaging.consumer.UserEventLogConsumer` | Reference `@KafkaListener` for this service's own events topic — demonstrates the required consumer-side idempotency pattern via `IdempotentEventHandler`, since nothing in the template otherwise exercises that half of `common-events`. Gated with `@ConditionalOnProperty` at the class level, not just via its container factory - Spring Boot's own Kafka auto-configuration otherwise supplies a default factory and the listener binds to it regardless. |
| `messaging.OutboxEventEntity` | Where this service's outbox rows live, and nothing else - it extends the platform's `AbstractOutboxEventEntity`, which owns the row shape and every query over it. The table name is the one part that genuinely differs between services. |
| `messaging.EventStoreConfig` | Points the platform's `JpaOutboxStore`/`JpaProcessedEventStore` at this service's two tables. Replaced about a hundred and ninety lines that were byte-identical in every service; what remains is the part only this service knows. |
| `messaging.publisher.KafkaEventPublisher` | Real Kafka binding for the common `EventPublisher` port. Keys records by the affected entity's id so a consumer sees all of one entity's events in order. Only invoked by `OutboxRelayScheduler`. |
| `messaging.publisher.LoggingEventPublisher` | Fallback `EventPublisher`: logs the event as structured JSON instead of publishing, so the service still runs (e.g. bare `java -jar`, no broker) without `OutboxRelayScheduler` needing to know. |
| `messaging.publisher.RabbitEventPublisher` | Standby RabbitMQ binding for the common `EventPublisher` port — in case Kafka doesn't get organizational clearance. Routes by `event.eventType()` on a topic exchange, the RabbitMQ analogue of Kafka's partition key. Only invoked by `OutboxRelayScheduler`, and only when RabbitMQ is the active broker (mutually exclusive with Kafka). |
| `persistence.entity.DocumentJpaEntity` | The JPA row shape for `documents`. Columns stay flat/individual, but the constructor accepts the same grouped `DocumentContext`/`DocumentFile`/`DocumentVersion` parameters the domain model does, purely to stay under the 7-parameter binding-rule limit. |
| `persistence.entity.UserJpaEntity` | The JPA row shape for `users`. Never returned from the API and never referenced outside infrastructure — `UserPersistenceMapper` is the only bridge to the domain model. |
| `persistence.mapper.DocumentPersistenceMapper` | Hand-written entity ⇄ domain converter for Document, same reasoning as `UserPersistenceMapper` - assembles `AuditMetadata` and the grouped value objects from individual flat columns. |
| `persistence.mapper.UserPersistenceMapper` | Hand-written (not MapStruct) entity ⇄ domain converter — hand-written specifically because it also assembles the `AuditMetadata` value object from five separate entity columns. |
| `persistence.repository.DocumentJpaRepository` | Spring Data JPA technology contract for `DocumentJpaEntity`, consumed only by `DocumentRepositoryImpl`. |
| `persistence.repository.UserJpaRepository` | Spring Data JPA technology contract (`extends JpaRepository<UserJpaEntity, UUID>`) — an implementation detail of *how* persistence happens, consumed only by `UserRepositoryImpl`. |
| `persistence.repository.UserJpaRepositoryCustom` / `UserJpaRepositoryCustomImpl` | The keyset-pagination query, deliberately built directly against `EntityManager`/`CriteriaBuilder` rather than `JpaSpecificationExecutor.findAll(Specification, Pageable)` - that method always issues a `COUNT(*)` alongside the content query, exactly the cost keyset pagination exists to avoid. `Impl`-suffixed so Spring Data's repository factory auto-detects and wires it (including constructor-injecting `EntityManager`) into `UserJpaRepository`. |
| `persistence.repositoryimpl.DocumentRepositoryImpl` | Implements the domain `DocumentRepository` port. `save` is always an insert - document rows are immutable once created (re-uploads are new versions, never overwrites), so there's no find-then-update branch unlike `UserRepositoryImpl`. |
| `persistence.repositoryimpl.UserRepositoryImpl` | Implements the domain `UserRepository` port on top of `UserJpaRepository` — the adapter that lets `UserService` stay ignorant of JPA entirely, and the place that declares the `users` constraints for `IntegrityViolations` to translate (published as a bean too, since a deferred flush raises the violation after this method has returned). `searchAfter` is the reference keyset-pagination implementation: orders by `(createdAt, id)` ascending, fetches `size + 1` rows to detect `hasMore` without a separate count, and is backed by the `idx_users_created_at_id` index (`V7__add_users_keyset_pagination_index.sql`). Uses Lombok's `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` in place of a hand-written constructor (see `MASTER_CODE_STANDARDS.md` section 5's Lombok convention) - the explicit `PACKAGE` access preserves this class's original package-private constructor visibility, since Lombok defaults to generating a `public` one. |
| `persistence.specification.UserSpecifications` | Builds the dynamic `Specification<UserJpaEntity>` for the Postgres-backed "contains"/status search, used by `UserRepositoryImpl.search` and by the `JpaUserSearchIndex` fallback (indirectly, via `UserRepository`). |
| `scanning.ClamAvVirusScanner` | Real ClamAV binding for the `VirusScanner` port - speaks ClamAV's INSTREAM protocol directly over TCP, no extra client library needed. |
| `scanning.FailClosedVirusScanner` | Default `VirusScanner` when no real scanner is configured - always reports `ERROR` (rejects the upload), the deliberate opposite of `LoggingEventPublisher`'s safe-to-degrade fallback. |
| `scanning.VirusScanConfig` | Chooses the `VirusScanner` implementation (ClamAV vs. fail-closed) based on whether `ipie.scanning.clamav.host` is configured - its own Javadoc documents how to add a cloud-native scanner later. |
| `storage.S3FileStorage` | S3-API `FileStorage` binding - works unchanged against AWS S3, MinIO, or any other S3-API-compatible target; only `S3StorageConfig`'s endpoint/credentials wiring changes between them. |
| `storage.S3StorageConfig` | Wires the `FileStorage` port to `S3FileStorage` and builds the `S3Client`/`S3Presigner` beans. Two things live verification caught that inspection alone missed: `S3Presigner.Builder` has no `forcePathStyle()` shortcut (needs the same `S3Configuration` a real client would use, or presigned URLs silently come back virtual-hosted-style), and the presigner must use `getPublicEndpoint()`, not `getEndpoint()` - the docker-network hostname this service reaches MinIO at is meaningless to an external caller fetching the presigned URL. |
| `storage.S3StorageProperties` | `ipie.storage.s3.*` - endpoint/region/credentials/bucket/path-style/SSE, with local-dev (MinIO) defaults baked in rather than left unset, unlike the event broker properties (FileStorage has no safe "do nothing" fallback). `publicEndpoint` is the internal-vs-external-address split `S3StorageConfig` needs for presigned URLs, falling back to `endpoint` when unset (the common case: a real AWS S3 endpoint is globally resolvable either way). |
| `search.UserSearchIndexConfig` | Chooses which `UserSearchIndex` bean is active (Elasticsearch vs. JPA fallback) based on whether an ES cluster is configured — wires all four `search/` subpackages together, so it stays un-nested at the top. |
| `search.document.UserDocument` | Elasticsearch projection of a user, indexed purely for the "search users" read path. Indexes username/email twice (once lower-cased) so case-insensitive "contains" search doesn't depend on analyzer behaviour. |
| `search.mapper.UserSearchDocumentMapper` | Converts between the domain `User` and its `UserDocument` projection. |
| `search.repository.UserDocumentRepository` | Spring Data Elasticsearch technology contract for `UserDocument` — the ES equivalent of `UserJpaRepository`. |
| `search.searchindex.ElasticsearchUserSearchIndex` | Real `UserSearchIndex` implementation backed by Elasticsearch; forces an immediate index refresh after writes so a just-created user is searchable right away. `searchAfter` uses Elasticsearch's `search_after` (sorted by `createdAt`/`idSort`, no `track_total_hits`) - the ES-side counterpart to `UserRepositoryImpl.searchAfter`'s Postgres keyset query. |
| `search.searchindex.JpaUserSearchIndex` | Fallback `UserSearchIndex` that delegates straight to `UserRepository`, so search still works (against Postgres) with no Elasticsearch cluster available. |

### `examples` — not production code

Reference-only classes, one per `ipie-common-libs` module - never wired into a controller or any
real request flow. Delete the ones a service does not need; they exist to be read, and a stale
example is worse than none.

The first four were written for the modules with no natural call site in this template's own
User/Document business logic. The rest were added on 2026-08-31 so that every module has a worked
example in one place. **Only the first four carry their own tests** - the others are compiled by the
build but not exercised, so treat them as reference rather than as proof the API still behaves as
described.

| Class | Why it exists | Tested |
|---|---|---|
| `CommonUtilsExample` | `DataMasking`/`IdGenerator`/`DateTimeUtils`/`Strings` usage. | yes |
| `CommonCacheExample` | `@Cacheable`/`@CachePut`/`@CacheEvict` usage. | yes |
| `CommonResilienceExample` | `@Retry`/`@CircuitBreaker` usage against a simulated flaky dependency. | yes |
| `CommonClientExample` | `InterServiceClient`/`ServiceRequest` usage against a hypothetical `notification-service`. | yes |
| `CommonAuditExample` | `@Auditable` and the manual `AuditEvent` path. | no |
| `CommonCoreExample` | Base exceptions and the `PageRequest`/`PageResult` paging primitives. | no |
| `CommonEventsExample` | Writing to the transactional outbox in the same transaction as the change. | no |
| `CommonFileStorageExample` | The `FileStorage`/`VirusScanner` ports and the quarantine-scan-promote flow. | no |
| `CommonI18nExample` | Resolving a message for the caller's locale. | no |
| `CommonObservabilityExample` | `LoggingContext` correlation id threaded through a call. | no |
| `CommonPersistenceExample` | `AuditableJpaEntity` standard columns and soft delete. | no |
| `CommonSecurityExample` | `@PreAuthorize` permission checks and `CurrentUserProvider`. | no |
| `CommonSessionExample` | `SessionService` idle window - status, extend, logout. | no |
| `CommonWebExample` | Offset and cursor paging responses, `@Idempotent`, `HttpRequestUtils.clientIp`. | no |

---

## 2. `ipie-common-libs` — shared library, organized by package

One Gradle module, consumed as a single library dependency by every service, never deployed on
its own. Sub-headings below (`common-core`, `common-web`, ...) are the package names
(`in.gov.ipie.common.core`, `in.gov.ipie.common.web`, ...), not separate Gradle modules — that was
true until 2026-07-20; see `ipie-common-libs/README.md` for the merge.

### `common-core` — framework-agnostic building blocks every layer can depend on

| Class | Why it exists |
|---|---|
| `correlation.CorrelationConstants` | The one place the correlation-id header name and MDC keys are defined, so `common-web` and `common-observability` agree on them without depending on each other. |
| `PlatformOverride` | Marks a class that deliberately replaces something the platform ships, and says why. The default is that a service does not write its own adapter for a platform port; this is the visible, in-writing opt-out that `noHandWrittenPlatformPortAdapters` checks for. |
| `exception.CommonErrorCode` | Generic, ready-to-use error codes (`NOT_FOUND`, `CONFLICT`, ...) for services that don't need a more specific code. |
| `exception.ConflictException` | Base exception for state conflicts (duplicate unique key, stale optimistic lock); `common-web` maps it to HTTP 409. |
| `exception.ErrorCode` | The contract every stable, service-specific error code must implement — codes are a contract with API consumers, so this exists to keep that shape consistent everywhere. |
| `exception.FieldError` | One field-level validation failure, surfaced under an `ApiError`'s `fieldErrors`. |
| `exception.IpieException` | Base type for every business/domain exception in the platform — `common-web`'s `GlobalExceptionHandler` is written against this, not against any specific subtype. |
| `exception.NotFoundException` | Raised when a requested resource doesn't exist; maps to HTTP 404. |
| `exception.ValidationFailedException` | Raised for business-rule validation beyond simple bean validation (cross-field/state-dependent rules); maps to HTTP 400 with field errors. |
| `model.AuditMetadata` | The standard five audit columns (`created_at/by`, `updated_at/by`, `version`) as one value object instead of five loose fields on every domain model. |
| `paging.PageRequest` | Framework-agnostic offset paging input — domain/application code doesn't depend on Spring Data's `Pageable` directly. Use for small/admin screens that need page numbers and a total count; issues a `COUNT(*)` and gets linearly slower the deeper you page, so avoid it for large/high-traffic listings. |
| `paging.PageResult` | Framework-agnostic offset-paged result, the counterpart to `PageRequest`. |
| `paging.Cursor` | Opaque keyset-pagination token — a `(createdAt, id)` tuple, encoded so API consumers only ever pass it back verbatim. Backs `CursorPageRequest`/`CursorPageResult`. |
| `paging.CursorPageRequest` | Framework-agnostic keyset ("seek") paging input, the scalable counterpart to `PageRequest` — no `COUNT(*)`, stays fast at any depth. Prefer this for large/high-traffic listings (see `UserRepositoryImpl.searchAfter` for the reference implementation). |
| `paging.CursorPageResult` | Framework-agnostic keyset-paged result. Deliberately carries no `totalElements`/`totalPages` — computing those needs the same `COUNT(*)` this paging style exists to avoid; `hasMore()` is all callers need. |

### `common-web` — the one API error/paging shape

| Class | Why it exists |
|---|---|
| `config.WebErrorAutoConfiguration` | Registers `GlobalExceptionHandler` for every service via Spring Boot auto-configuration, since a plain `@RestControllerAdvice` in a shared jar is never picked up by a service's own component scan. |
| `config.OpenApiAutoConfiguration` | The one OpenAPI bean, titled from `spring.application.name` and described by `ipie.openapi.description`. Every service declared this itself, which is how all three ended up serving the template's own description as their public API summary. |
| `error.ApiError` | The one error response JSON shape every iPIE API returns — no service invents its own. |
| `error.GlobalExceptionHandler` | The single place that translates exceptions (domain, validation, security, unexpected) into `ApiError`. Services extend this rather than adding their own differently-shaped error handlers. Uses Lombok's `@Slf4j` instead of a hand-written `Logger` field (see `MASTER_CODE_STANDARDS.md` section 5's Lombok convention). Overrides `handleTypeMismatch` so an invalid enum query/path param (e.g. `?status=BOGUS`, `?sortBy=BOGUS`) returns this platform's `ApiError` shape instead of Spring's default `ProblemDetail` - the same fix that makes `UserSortField` validation return a clean `400`. Its catch-all `handleUnexpected` logs `common-utils`'s `ExceptionUtils.getRootCause` alongside the full trace, since `ex` itself is often just a generic wrapper (a Kafka/RabbitMQ listener invocation wrapper, for example) whose own class/message says nothing actionable. |
| `paging.PageResponse` | The common JSON shape for an offset-paged API response — controllers return this, never a raw `List`. |
| `paging.CursorPageResponse` | The common JSON shape for a keyset-paged API response, the counterpart to `PageResponse` for `CursorPageResult`. |

### `common-security` — the approved JWT/permission baseline

| Class | Why it exists |
|---|---|
| `config.IpieSecurityProperties` | `ipie.security.*` settings: the permissions JWT claim, public (unauthenticated) paths, and allowed CORS origins. |
| `config.JwtPermissionsConverter` | Turns the configured permissions claim on a validated JWT into `PERMISSION_*` Spring Security authorities, independent of the identity provider's token shape. |
| `config.ResourceServerAutoConfiguration` | The approved security baseline applied to every service: JWT validation, permission-authority mapping, CORS, and a `ipie.security.enabled=false` local-dev escape hatch. A service overrides it only by defining its own `SecurityFilterChain`. |
| `secret.SecretHasher` | The one way a bearer secret is stored so a database read does not yield a working one, and the place the choice between the two modes is explained. Two services answered this separately and correctly, with the reasoning recorded nowhere - so the third would have guessed, and a wrong guess is invisible until someone reads the database. |
| `secret.PepperedSecretHasher` | HMAC-SHA256 under a configured pepper, for a secret small enough to enumerate. A six-digit OTP has a search space of one million, so an unkeyed digest of it is reversible by anyone holding the database. Construction fails without a pepper rather than falling back to an unkeyed digest, which would look identical everywhere while protecting nothing. |
| `secret.DigestSecretHasher` | Plain SHA-256, for a secret this platform generated with 256 bits of randomness - no dictionary to run, so a pepper would add a key to manage for no gain. |
| `secret.SecretGenerator` | Issues those tokens: 32 bytes of `SecureRandom`, URL-safe Base64, because the token travels in a link. Deliberately not a UUID - a version-7 id encodes its creation time, which narrows a guess from a neighbouring token. |
| `context.CurrentUser` | The authenticated caller for the current request (id, username, permissions), resolved from the validated JWT. |
| `context.CurrentUserProvider` | Port through which application/domain code reads the caller, instead of touching `SecurityContextHolder` directly — keeps business code framework-independent and easy to stub in tests. |
| `context.SecurityContextCurrentUserProvider` | Default `CurrentUserProvider`, backed by Spring Security's JWT authentication token. |
| `permission.DefaultPermissionEnforcer` | Default `PermissionEnforcer`: checks the current user's permissions, honouring the `ipie.security.enabled=false` escape hatch. |
| `permission.PermissionAuthorities` | Converts a business permission name (`"USER_WRITE"`) into the Spring Security authority string, so business code and `@PreAuthorize` checks agree on the shape without either side hard-coding it twice. |
| `permission.PermissionEnforcer` | Port for programmatic permission checks (`require(permission)`), preferred over `@PreAuthorize` in this template because it also honours the local-dev escape hatch uniformly. |
| `keycloak.KeycloakTokenClient` | Refreshes an access token via Keycloak's `refresh_token` grant - opt-in on `ipie.security.keycloak.token-uri`, used by `common-client`'s `TokenRelayInterceptor` to avoid relaying an already-expired token. |
| `keycloak.RefreshTokenContextHolder` | Thread-local carrying the current request's refresh token, if the caller supplied one. |
| `keycloak.RefreshTokenCaptureFilter` | Captures `X-Refresh-Token` into the holder above - not auto-registered; a real security trade-off (see its Javadoc) a service opts into deliberately. |
| `keycloak.admin.KeycloakAdminClient` | Creates a new Keycloak client (client-id/secret) via the Admin REST API, automating the "register a Keycloak client for the service" step - a platform-provisioning capability requiring its own explicit `ipie.security.keycloak.admin.enabled=true` gate, deliberately not something a typical business microservice carries in normal runtime config (see its Javadoc). |
| `hmac.HmacSignature` | The one canonicalization + HMAC-SHA256 computation both `common-client`'s `HmacSigningInterceptor` and this module's own verification filter use, so the two can never drift apart. |
| `hmac.HmacSigningProperties` | `ipie.security.hmac.*` - shared signing keys, clock-skew tolerance, nonce TTL, and which paths require a valid signature. |
| `hmac.NonceStore` / `InMemoryNonceStore` / `RedisNonceStore` | Replay protection - a nonce may be consumed exactly once; Redis-backed (multi-instance-safe) when configured, in-memory fallback otherwise, the same precedence `common-cache` uses for its `CacheManager`. |
| `hmac.HmacSignatureVerificationFilter` | Verifies the signature/timestamp/nonce on every request to a configured protected path - not auto-registered, a service wires it into its own `SecurityFilterChain`. |
| `hmac.CachedBodyHttpServletRequest` | Buffers the request body once so the verification filter can read it for hashing and a controller can still read it again afterward - a plain request's input stream is otherwise consumable only once. |

### `common-observability` — correlation and structured logging

| Class | Why it exists |
|---|---|
| `config.ObservabilityAutoConfiguration` | Registers the correlation-id servlet filter for every service that depends on this module. |
| `correlation.CorrelationIdFilter` | Reads/generates the `X-Correlation-Id` header, publishes it to the MDC for the request's lifetime, and echoes it back — the basis for correlating log lines across services. Sanitizes the inbound value against a strict pattern first, since it's echoed back into a response header (CRLF/header-injection risk otherwise). |
| `correlation.LoggingContext` | Helper application code uses to set structured MDC fields (case id, user id, correlation id) instead of calling `MDC` directly, keeping the actual MDC key names an internal detail of this module. |

### `common-audit` — the business/security audit trail

| Class | Why it exists |
|---|---|
| `AuditRecorder` | Port every service writes audit records through. `OutboxAuditRecorder` (durable) is used automatically once a service has an `OutboxStore` bean; `LoggingAuditRecorder` is the fallback otherwise. |
| `outbox.OutboxAuditRecorder` | Durable `AuditRecorder` - writes through the same transactional outbox `common-events` defines for domain events, reusing the existing Kafka/RabbitMQ delivery pipeline (see `ipie-service-template`'s `JpaOutboxStore`/`OutboxRelayScheduler`) instead of a second, parallel one. |
| `LoggingAuditRecorder` | Reference `AuditRecorder`: writes each event as one structured JSON line under a dedicated `AUDIT` logger. Used only when no `OutboxStore` bean is present. |
| `annotation.Auditable` | Marks an application-service method as an auditable business action; `AuditAspect` turns each successful call into one `AuditEvent`. `entityId`/`caseId` are SpEL expressions so the annotation stays declarative. |
| `aspect.AuditAspect` | Turns a `@Auditable` method call into an `AuditEvent` after the method completes successfully (so a rolled-back action never produces a misleading audit record), and swallows any audit-recording failure rather than failing the business operation it describes. |
| `config.AuditAutoConfiguration` | Wires the `AuditRecorder` precedence chain (outbox-backed, then logging fallback) and the `AuditAspect` into every consuming service via auto-configuration. |
| `model.AuditEvent` | A single audit record shape: who, what, when, from where, which service/entity/case, old/new values where required. |
| `model.AuditEventType` | The two audit categories (`SECURITY`, `BUSINESS`) — kept separate from ordinary application logs, which are `common-observability`'s concern instead. |

### `common-events` — the broker-agnostic eventing contract

| Class | Why it exists |
|---|---|
| `envelope.EventEnvelope` | The one envelope shape every business event is published in, carrying a separate *event contract* version from the code version, so breaking changes can be rolled out without breaking existing consumers. |
| `idempotency.IdempotentEventHandler` | Wraps event handling in the required check-then-mark idempotency pattern, so consumers don't each reimplement duplicate-delivery handling by hand. |
| `idempotency.ProcessedEventStore` | Port for tracking which inbound event ids a consumer has already handled — each service backs this with its own storage, per "each service owns its data." |
| `jpa.AbstractOutboxEventEntity` | The outbox row shape, held once. A service subclasses it with an `@Table` and declares nothing else - the table name is the only part that genuinely differs, because ipie-iam-service shares ipie-user-service's database and prefixes its platform tables. |
| `jpa.AbstractProcessedEventEntity` | The consumed-event-id row shape, same arrangement as above. |
| `jpa.JpaOutboxStore` | The JPA implementation of `OutboxStore`, which every service used to keep a byte-identical copy of. Works through the `EntityManager` rather than a Spring Data repository, because the entity type is declared by the service; it is passed in with a method reference, so an entity that cannot be constructed fails the compile instead of the first event published. |
| `jpa.JpaProcessedEventStore` | The JPA implementation of `ProcessedEventStore`, same reasoning. |
| `outbox.OutboxRelayScheduler` | Drains the outbox into whichever publisher is active and applies the retention policy. A service needs `@EnableScheduling` and nothing else; interval, batch size and retention are properties. |
| `config.OutboxRelayAutoConfiguration` | Registers the relay for any service that has both a store and a publisher. `@ConditionalOnBean` is safe here because auto-configuration is processed after the service's own beans. |
| `outbox.OutboxRelay` | Drains an `OutboxStore` into an `EventPublisher`, one batch at a time — the half of the transactional outbox pattern that's pure orchestration, so it stays framework/persistence-agnostic like the rest of common-events; a service supplies its own scheduling. |
| `outbox.OutboxStore` | Port for the transactional outbox pattern: `save` must run inside the same DB transaction as the business change, so the write and the event are atomic without a distributed transaction across Postgres and the broker. |
| `publisher.EventPublisher` | The port application services publish events through, deliberately silent on which broker (Kafka, RabbitMQ, SNS, ...) is behind it — see `ipie-service-template`'s `KafkaEventPublisher`/`LoggingEventPublisher` for the concrete bindings each platform provides once. Only ever called by `OutboxRelayScheduler` now, never by business code directly. |

### `common-resilience` — timeouts, retry and circuit-breaking defaults

| Class | Why it exists |
|---|---|
| `config.IpieResilienceAutoConfiguration` | Applies the shared Retry/CircuitBreaker/Bulkhead/TimeLimiter defaults and an explicit `RestClient` connect/response timeout to every consuming service, with no per-service YAML required for the common case. |
| `config.IpieResilienceHttpProperties` | `ipie.resilience.http.*` — the connection/response timeout applied to the auto-configured `RestClient`, kept deliberately separate from `resilience4j.timelimiter.*` since a `TimeLimiter` only bounds async calls, not blocking ones. |
| `config.YamlPropertySourceFactory` | Lets `@PropertySource` load the resilience defaults from a non-`application.yml`-named YAML file — shipping an `application.yml` inside a library jar is fragile because of classpath resolution order. |
| `exception.TransientDependencyException` | Marks an outbound call failure as safe-to-retry (timeouts, connection resets, 5xx). Only genuinely transient failures should use this — the default retry config only retries `IOException`, `TimeoutException`, and this type, never functional/business errors. |

### `common-client` — generic, secured inter-service HTTP client

| Class | Why it exists |
|---|---|
| `InterServiceClient` / `DefaultInterServiceClient` | The one port every service calls another service through - generic `exchange`/`execute` methods cover every HTTP method and request/response shape, so no per-target-service or per-verb client is hand-written. Resolves the target's base URL, decorates the call with a per-service-name Retry/CircuitBreaker/Bulkhead/RateLimiter quartet (inheriting `common-resilience`'s shared defaults), records an audit event per call, and maps 5xx/IO failures to `TransientDependencyException` vs 4xx to `RemoteServiceException`. |
| `request.ServiceRequest` | One outbound call's method/path/query/headers/body/idempotency-key - enforces the Idempotency-Key control at build time for every `POST`/`PUT`/`PATCH`/`DELETE`, the same control `ipie-service-template`'s `UserController` implements by hand. |
| `config.ResilienceRegistries` | Groups the four resilience4j registries `DefaultInterServiceClient` decorates every call with into one parameter, keeping its constructor and the autoconfiguration's wiring method under Checkstyle's parameter-count limit. |
| `config.InterServiceClientProperties` | `ipie.client.*` - service-name-to-base-URL resolution; a documented placeholder for real service discovery, not the platform's final answer to it. |
| `config.InterServiceSecurityProperties` | `ipie.client.security.*` - selects `CLIENT_CREDENTIALS` (default, this service authenticates as itself against Keycloak), `TOKEN_RELAY` (forwards the inbound caller's own JWT), or `NONE`; also gates the additive HMAC-signing control. |
| `config.OpaAuthorizationProperties` | `ipie.client.security.opa.*` - self-hosted OPA base URL/policy path, and whether an unreachable OPA fails closed (default) or open. |
| `config.InterServiceMtlsProperties` | `ipie.client.security.mtls.*` - key store (this service's own identity), trust store (the peers it will accept), protocols and ciphers for transport-level mutual authentication. `enabled` defaults to **false**: mTLS is a per-deployment decision that ships with the key material, and no local or CI flow has certificates. |
| `config.InterServiceClientAutoConfiguration` | Wires the `InterServiceClient` bean plus the correlation-id interceptor and (when `spring.security.oauth2.client.registration.*` is configured) the OAuth2 client-credentials manager; composes HMAC signing and OPA authorization on top of the mode-selected interceptor when each is enabled - OPA outermost, since it must run before any signing/authentication work. |
| `security.OAuth2ClientCredentialsInterceptor` | Attaches this service's own Keycloak-issued access token (client-credentials grant) to every outbound call - the default, most secure option, since the downstream service sees this service's identity rather than a relayed user token. |
| `security.TokenRelayInterceptor` | Forwards the inbound caller's own JWT unchanged - opt-in, for the specific calls that must be authorized against the original user's permissions; refreshes an expired token via `common-security`'s `KeycloakTokenClient` when configured. |
| `security.HmacSigningInterceptor` | Adds HMAC signature/timestamp/nonce headers on top of whichever mode above is selected - defense in depth for genuinely high-sensitivity regulatory calls, verified by `common-security`'s `HmacSignatureVerificationFilter`. |
| `security.InterServiceMtlsSslBundles` | Turns those properties into a Spring `SslBundle` for the `InterServiceClient` transport only - deliberately not a global `RestClientCustomizer`, which would also swap trust material for the Keycloak and pillar-IdP clients that sit outside the platform CA. Fails at startup, naming the property at fault, rather than degrading to one-way TLS. Inbound is Spring Boot's own `server.ssl.client-auth`, not rebuilt here. |
| `security.OpaAuthorizationInterceptor` | Policy-enforcement point: asks a self-hosted OPA instance whether this call is allowed at all before it proceeds, extending the platform's user-facing OPA/ABAC pattern to service-to-service calls; fails closed by default when OPA is unreachable (see `deploy/opa/policies/interservice.rego`). |
| `correlation.CorrelationPropagationInterceptor` | Carries the current request's correlation id (see `common-observability`'s `CorrelationIdFilter`) onto the outbound call, so cross-service log correlation holds for calls made through this client too. |
| `exception.RemoteServiceException` | A downstream `4xx` - deliberately not retried and not an `IpieException` subtype (see the module's README, "Exception model"). |

### `common-testing` — shared test infrastructure

| Class | Why it exists |
|---|---|
| `archunit.LayeredArchitectureRules` | The layering rules (controllers-don't-touch-repositories, domain-doesn't-depend-on-infrastructure, etc.) every service's own `ArchitectureTest` applies against its own base package — defined once so every service enforces the same rules identically. |
| `containers.ElasticsearchIntegrationTest` | Testcontainers mixin providing a real, version-pinned Elasticsearch instance for integration tests. An interface (not an abstract class) so a test needing both this and `PostgresIntegrationTest` can implement both. |
| `containers.PostgresIntegrationTest` | Testcontainers mixin providing a real PostgreSQL instance for integration tests, shared across the JVM for speed. |
| `containers.RedisIntegrationTest` | Testcontainers mixin providing a real Redis instance (pinned to `redis:7-alpine`, matching `docker-compose.yml`'s `redis` service) for integration tests - a plain `GenericContainer` since no dedicated Testcontainers Redis module is used by this project's Testcontainers version. Also an interface, for the same multi-mixin-composition reason as the other two. |

### `common-cache` — Spring cache abstraction auto-configuration

| Class | Why it exists |
|---|---|
| `config.IpieCacheAutoConfiguration` | Picks the `CacheManager` a consuming service gets, first match wins: Redis-backed (Lettuce, JSON values via the app's own `ObjectMapper`) when Redis is on the classpath **and** `spring.data.redis.host` is set, a `NoOpCacheManager` otherwise - so `@Cacheable`/`@CacheEvict`/`@CachePut` methods run correctly (just uncached) with zero per-service YAML either way. Its Redis wiring is a nested, `@ConditionalOnClass(RedisConnectionFactory.class)`-guarded configuration, not a `@Bean` directly on the outer class, so the Redis-absent case can't fail classloading of the outer autoconfiguration itself. |
| `config.IpieCacheProperties` | `ipie.cache.ttls.<name>` per-cache-name TTL overrides. |

### `common-utils` — small dependency-free helpers

| Class | Why it exists |
|---|---|
| `datetime.DateTimeUtils` | ISO 8601 date/time helpers, per the platform's "dates are always ISO 8601" standard. |
| `id.IdGenerator` | Id and human-readable business-reference-number generation, so services don't each invent their own format. |
| `masking.DataMasking` | Masks sensitive values (email, PAN, Aadhaar, generic) before they're logged or displayed, per the platform's data-masking standard — business modules call these instead of writing ad hoc masking. |
| `text.Strings` | Small null/blank-safe string helpers, to avoid ad hoc null checks being reimplemented per service. |
| `json.JsonUtils` | Jackson (de)serialization helpers for code outside the Spring MVC request/response cycle (batch jobs, event bodies, cache values) - services with a Spring-managed `ObjectMapper` bean should keep using that instead. |
| `validation.ValidationUtils` | Format validators for India-specific identifiers (PAN, Aadhaar, mobile, pincode) and simple shapes (email, UUID) - pairs with `masking.DataMasking`, which formats the same identifiers for display rather than validating them. Not a replacement for `common-core`'s `ValidationFailedException`/`FieldError`, which carry a failed validation result across a service boundary. |
| `collection.CollectionUtils` | Null-safe collection helpers (empty checks, `nullToEmpty`, chunking/partitioning). |
| `network.NetworkUtils` | Dependency-free IP helpers (parsing `X-Forwarded-For`, IPv4/IPv6 format validation, masking an IP for logs) - takes plain strings rather than a servlet request, since this module has no web dependency. |
| `exception.ExceptionUtils` | Generic `Throwable` inspection (root cause, stack trace to string, typed cause lookup) - unrelated to `common-core`'s exception *type hierarchy* (`IpieException`, `ErrorCode`, `FieldError`), which models business/API errors rather than inspecting arbitrary caught exceptions. |

### `common-file-storage` — the file-upload contract (storage, scanning, validation, naming, hashing)

| Class | Why it exists |
|---|---|
| `storage.FileStorage` | The port every service uploads/downloads through, deliberately silent on the object store behind it - same reasoning as `EventPublisher` for the messaging broker. |
| `scanning.VirusScanner` | The port every uploaded file is scanned through before leaving quarantine. No "unconfigured = assume clean" implementation ships here on purpose - see `ipie-service-template`'s `FailClosedVirusScanner`. |
| `scanning.ScanResult` / `scanning.ScanStatus` | The outcome of one scan pass (`CLEAN`/`INFECTED`/`ERROR`) - `ERROR` is never treated as clean. |
| `validation.AllowedFileType` | One entry in a per-use-case whitelist. Deliberately no global default whitelist ships here - "per use case" is the whole point (a court-order upload and a profile photo shouldn't share one list). |
| `validation.FileTypeValidator` | Validates a file's *actual* sniffed content (Apache Tika magic-byte detection), never the filename extension or client-supplied `Content-Type` header, both trivially spoofed. |
| `validation.FileSizeValidator` | Enforces a per-file size limit - a service-layer backstop, not the only control (a gateway should also enforce this where one exists). |
| `naming.StorageKeyGenerator` | Builds UUID-based storage keys structured by module/entity/doc-type, never the user-supplied filename - and the quarantine/permanent key pair for the quarantine-first pattern. |
| `hash.FileHasher` | SHA-256 hashing for the dedup/integrity metadata every uploaded file's row carries. |
| `exception.UnsupportedFileTypeException` / `FileTooLargeException` / `MalwareDetectedException` / `ScanUnavailableException` | The typed, specific failures the upload pipeline can produce - each a real `IpieException` subtype, giving callers an actionable `ApiError` instead of one generic failure. |

### `common-session` — idle-session timeout, independent of JWT expiry

| Class | Why it exists |
|---|---|
| `config.SessionProperties` | `ipie.session.*` - idle timeout, warning threshold, extend-by duration, on/off (on by default). |
| `config.SessionAutoConfiguration` | Wires the `SessionStore` precedence (Redis-backed, then in-memory fallback), `SessionService`, the auto-registered `SessionActivityFilter`, and `SessionController`. |
| `SessionService` | The idle-session business logic: `touch`/`status`/`extend`/`logout`. |
| `SessionStatus` | The frontend-facing snapshot - `active`, `remainingSeconds`, `warningThresholdSeconds`. |
| `store.SessionStore` / `InMemorySessionStore` / `RedisSessionStore` | Port backing one user's session expiry - Redis-backed (multi-instance-safe, TTL as the expiry mechanism) when configured, in-memory fallback otherwise, the same precedence `common-cache`/`common-security`'s `NonceStore` already use. |
| `web.SessionActivityFilter` | Touches the caller's session on every authenticated request - a plain servlet filter registered at deliberately low priority so it runs after Spring Security's own chain has populated the security context. |
| `web.SessionController` | The `/api/v1/session/{status,extend,logout}` REST surface every service gets automatically - a common-lib module registering a real Spring MVC component, the same precedent `common-web`'s `GlobalExceptionHandler` sets. |

---


### Packages added since the first pass (added 2026-08-13)

The sections above were written on 2026-07-20 and did not cover these.

**`common-security` — `password` and `ratelimit` subpackages**

| Class | Why it exists |
|---|---|
| `password.PasswordPolicy` | The one definition of what makes an acceptable password, so every place a user chooses or changes one applies the same rule and reports it the same way. 12-character minimum, 100 maximum, character-class requirements as independent lookaheads. Under the credential re-architecture this is **the control, not a mirror** of Keycloak's realm policy: `ipie-iam-service` enforces it on its own request records, because Keycloak no longer holds passwords. |
| `ratelimit.RateLimiter` | Fixed-window throttling for a caller-supplied key (typically `path:clientIp`). A port rather than a concrete Redis dependency, the same precedence `cache` and `session` use. |
| `ratelimit.RedisRateLimiter` / `InMemoryRateLimiter` | Redis-backed counter shared across every instance behind a load balancer; in-memory fallback throttles within one JVM only — correct for local dev, not for a horizontally scaled deployment. |
| `ratelimit.RateLimitFilter` | Rejects with `429` once a caller exceeds its rule. **Rule order matters — the first match wins**, so a specific path must precede any pattern that would swallow it. |
| `ratelimit.RateLimitProperties` | `ipie.security.rate-limit.rules[]` — a list rather than one blanket limit, so an unauthenticated write path can be budgeted far more tightly than an authenticated read. |
| `ratelimit.RateLimitAutoConfiguration` | Picks the Redis or in-memory implementation from `spring.data.redis.host`. |

**`i18n` — one supported-locale list for the whole platform**

| Class | Why it exists |
|---|---|
| `IpieI18nProperties` | `ipie.i18n.*` — the canonical supported-locale list. Deliberately **not** a per-service choice: Keycloak, notification templates and every API error must agree on which locales exist, or a user gets a localized page and an English error. |
| `MessageResolver` | The one way a service turns a stable `ErrorCode` into a message localized for the request's locale, so error identity stays machine-readable while its text is translatable. |
| `SupportedLocaleResolver` | Restricts resolution to the canonical list instead of honouring any `Accept-Language` a client sends. |

**`persistence` — the shared entity base, and constraint translation**

| Class | Why it exists |
|---|---|
| `JpaAuditingAutoConfiguration` | Fills `created_by`/`updated_by` from the authenticated caller, falling back to `system` for an internal path like an event consumer that legitimately has no security context. Identical in every service before this. |
| `IntegrityViolations` | Turns a `DataIntegrityViolationException` into the error the failing constraint actually means. Repositories used to answer every violation with one message, so a duplicate phone number was reported as a duplicate email and a foreign-key bug as the caller's conflict. Each repository declares the constraint names of the table it owns - only it knows them - and publishes the declaration as a bean, because the same violation surfaces either inside the repository call or from the transaction commit, depending on when Hibernate flushes. An undeclared constraint is rethrown rather than guessed at. |
| `IdCollisionException` | A generated id that duplicated an existing row - deliberately not a `ConflictException`, because a conflict means the caller's request is wrong and will fail again, while this means the same request with a fresh id would succeed. With version-7 ids it should never appear; if it does, the fact worth knowing is that id generation (or hand-written seed data) produced a duplicate, not that an insert failed. |
| `AuditableJpaEntity` | The shared audit + soft-delete columns every JPA entity inherits platform-wide (`createdAt/createdBy/updatedAt/updatedBy/version/isActive/deletedAt/deletedBy`) — standards §8. Entities that are append-only records, join tables or single-use token rows deliberately do **not** extend it; their own edit history is not meaningful. |

## Keeping this doc current

When you add a class to `ipie-service-template` or any `ipie-common-libs` package, add one row
here explaining *why* it exists (not what it does — that's the code's job). When you delete or
rename a class, update or remove its row in the same change. `MASTER_CODE_STANDARDS.md` should
link here rather than re-explaining an individual class's purpose.
