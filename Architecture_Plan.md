% Architecture Plan — Credentials, Asynchronous Registration and Access Management
% iPIE Platform (ipie-platform-mca)
% Implemented 2026-08-11/12 · verified end to end 2026-08-12

# 1. What this describes

The platform's registration and authentication design as **built and verified**, spanning
`ipie-user-service`, `ipie-iam-service`, `ipie-communication-service`, the `ipie-keycloak-spi`
module and the Keycloak realm.

Each service repository has its own architecture plan covering its half in detail, in its
`docs/` folder. That folder is local-only in every repository, so those plans are on disk
beside the code rather than in git.
This document is the one that explains how they fit together, and is the one to read first.

**Status:** registration through to a working login was proven end to end against the real stack on
2026-08-12. Sections marked *Not yet built* are honest gaps, not omissions.

# 2. The four decisions everything follows from

| # | Decision |
|---|---|
| **D1** | Keycloak issues tokens only. **It never stores a password.** |
| **D2** | `ipie-iam-service` is the credential authority: it stores password hashes, verifies them, and enforces policy. |
| **D3** | Communication between services is asynchronous, with exactly one documented exception (§6). |
| **D4** | Registration collects no password. |

A fifth decision governs data placement: `ipie-user-service` and `ipie-iam-service` **share the
`ipie_user_service` database**, with separate Flyway histories. Its consequences are in §9.

# 3. Who owns what

"User data" is three separate things, and only two of them are Identity and Access Management:

1. **The account** — the authentication subject → `ipie-iam-service`
2. **Entitlements** — what that account may do → `ipie-iam-service`
3. **The person** — who they are as a business fact → `ipie-user-service`

The third is a business domain that happens to be about people. A postal address or a professional
registration number is no more IAM's concern than an invoice is.

**The test for anything new:** *could this exist and still be meaningful before the person has an
account?* If yes it belongs to `ipie-user-service`; if it is meaningless without an account it
belongs to `ipie-iam-service`. A registration draft, an address and an identity proof pass the first
test. A password, a role grant and a lockout counter fail it.

| Service | Owns | Must never hold |
|---|---|---|
| `ipie-user-service` | The person: registration wizard and drafts, registration status, email-OTP verification of the contact address, pillar-admin approval, profile, identity proof, professional details, organisations, authoritative `pillar_links`, people search | Any credential — password, hash, or credential-setting token — in a field, DTO, event or log. Holds `keycloak_user_id` as a reference only |
| `ipie-iam-service` | The account and its entitlements: account lifecycle in Keycloak, credentials, authentication factors and lockout, roles and permissions, federation config, the login-path pillar projection, and the `keycloak-spi` module | Profile or PII beyond the identifiers authentication needs |
| `ipie-communication-service` | Everything leaving the platform: email/SMS delivery, templates, the per-purpose recipient registry, channel preferences, and the notification log with its DPDP masking | Business state anyone reads back — it is a sink, not a source |
| Keycloak | Token issuance and validation, identity attributes, realm roles, SSO federation | **A password** |

## 3.1 Two boundary cases, resolved

**Email** is both a contact detail and a login identifier. `ipie-user-service` is authoritative;
Keycloak holds a login-path copy. A profile email change must be re-verified before it propagates,
or the login identifier becomes changeable without proof.

**Account status.** `UserStatus.ACTIVE/INACTIVE` is a *business* state owned by
`ipie-user-service`; Keycloak's `enabled` flag is the *access consequence*, applied by iam.
Collapsing them makes "deactivated for business reasons" and "locked for security reasons" one
field with two meanings.

# 4. The registration flow, as built

```
browser ──> ipie-user-service   POST /api/v1/registrations
                                POST /api/v1/registrations/{id}/email-otp
                                POST /api/v1/registrations/{id}/email-otp/confirm
                                POST /api/v1/registrations/{id}/complete   <- NO password field
                                  · writes its own row, status PROVISIONING
                                  · publishes ACCOUNT_PROVISIONING_REQUESTED
                                  · returns immediately; keycloakUserId is null

ipie-iam-service  <- ACCOUNT_PROVISIONING_REQUESTED
                                  · creates the Keycloak account WITHOUT credentials
                                  · mints a single-use credential-setup token (stores SHA-256 only)
                                  · publishes ACCOUNT_CREDENTIAL_SETUP_REQUESTED  -> comms
                                  · publishes ACCOUNT_PROVISIONED (no token)      -> user-service

ipie-user-service <- ACCOUNT_PROVISIONED
                                  · stamps keycloakUserId, status UNVERIFIED
                                  · publishes USER_REGISTRATION_COMPLETED

ipie-communication-service
                  <- ACCOUNT_CREDENTIAL_SETUP_REQUESTED -> "set your password" mail to the REGISTRANT
                  <- USER_REGISTRATION_COMPLETED        -> approval mail to the STAKEHOLDER ADMIN
                                                        -> "registration received" note to the registrant

browser ──> ipie-web  /set-password?token=…
        ──> ipie-iam-service  POST /api/v1/credentials/password   (public, token-authorised)

browser ──> Keycloak  token endpoint ──> SPI ──> iam verify ──> access token
```

## 4.1 Why `keycloakUserId` is null when `complete` returns

Because the Keycloak account genuinely does not exist yet. **That null is the proof the design
works.** Previously `complete` called iam synchronously, which called Keycloak, waited for the
account, and only then responded — so registration failed outright whenever Keycloak was slow or
briefly unavailable, on a path a citizen sits in front of. A populated id in that response would
mean the request had blocked until Keycloak answered.

The cost is a real, brief window where the person exists and the account does not. That is what the
`PROVISIONING` status names, and why it is a visible state rather than an implicit one: a
registration sitting there means the provisioning event was not processed — check
`ipie.events.dlq`.

## 4.2 Two tokens, never one

| Token | Minted by | Emailed to | Authorises | Consumed by |
|---|---|---|---|---|
| `credential_setup_token` | iam | **the registrant** | setting the initial password | iam |
| `verification_token` | user-service | **the pillar-admin mailbox** | approving the registration | user-service |

They go to different people and grant different powers. A single shared token would let the
pillar admin set any pending user's password, or let a user approve their own registration —
whichever way it were routed, one of the two controls would be defeated.

**This is the defect that dead-ended the previous design.** One token served both purposes, and the
only email carrying it went to the admin mailbox, so the registrant never received a way to set a
password at all. See §11.

# 5. Credential handling

**A reusable credential never leaves the credential authority.** A password may travel only on a
synchronous, TLS-protected call whose destination is `ipie-iam-service`. It is never written to the
outbox, published to a broker, persisted outside `user_credentials`, recorded in an audit event, or
logged.

**Single-use, short-TTL secrets may travel on events** — provided they are single-use, short-lived,
masked in logs and audit, and stored hashed. The credential-setup token and the registration email
OTP both qualify. This is a deliberate line, not an absolute prohibition: the platform already
carried the OTP this way before this work.

Storage: Argon2id at OWASP parameters (m=19456 KiB, t=2, p=1, salt 16, hash 32). Setup tokens are
stored as a **SHA-256 fingerprint only** — a database read, a backup, or a support query yields
nothing usable.

# 6. Login, and the one synchronous call

```
browser ──> Keycloak token endpoint (unchanged for ipie-web)
        ──> direct-grant flow ──> ipie-direct-grant-validate-password (SPI)
        ──> HMAC-signed POST /internal/credentials/verify ──> ipie-iam-service
        ──> Keycloak issues the token
```

**This hop is synchronous and cannot be otherwise.** Authentication is a question whose answer the
user is waiting for; there is no asynchronous form of it. It is the single documented exception to
D3 and should be recorded as such rather than left looking like an oversight.

What makes it acceptable: it is one hop, Keycloak → iam, and it does **not** pass through
`InterServiceClient`, so it is not subject to that bulkhead (10 concurrent per target, the 11th
rejected outright). It carries its own short timeout and no retry — a password check is not safely
repeatable, since each attempt is a login attempt as far as brute-force counting is concerned.

**Both authenticators fail closed.** An unreachable iam refuses the login rather than allowing it,
and reports *temporarily unavailable* rather than *invalid credentials* — telling someone their
password is wrong when it was never checked sends them to reset a password that was fine.

# 7. Keycloak realm configuration

All of the following is in `deploy/keycloak/realm-export.json`.

| Setting | Value | Why |
|---|---|---|
| `browserFlow` | `ipie-browser` | binds `ipie-auth-username-password-form` |
| `directGrantFlow` | `ipie-direct-grant` | binds `ipie-direct-grant-validate-password`; this is the flow ipie-web's login uses |
| `passwordPolicy` | `length(12) and upperCase(1) and lowerCase(1) and digits(1) and specialChars(1) and notUsername and notEmail` | mirrored by `PasswordPolicy` in common-libs |
| `bruteForceProtected` | `true`, `failureFactor: 10`, `maxFailureWaitSeconds: 900`, `permanentLockout: false` | temporary by design — a permanent lockout lets anyone lock a citizen out by failing logins on their behalf |

**Flows** (all `builtIn: false`): `ipie-browser` → `ipie-browser-forms` → `ipie-browser-conditional-otp`;
`ipie-direct-grant` → `ipie-direct-grant-conditional-otp`; plus the pre-existing
`ipie-pillar-first-broker-login`.

**Realm role `CREDENTIAL_VERIFY`**, granted to the `service-account-ipie-keycloak-spi` service
account alongside `PILLAR_LINK_RESOLVE` and `LOGIN_NOTIFY`. It gates
`POST /internal/credentials/verify`. **Deliberately its own permission** rather than reusing
`ACCOUNT_PROVISION`, which `ipie-user-service` holds — that service must never be able to test
passwords, which is the whole point of moving credentials to iam.

*Without this role every verify call returns 403, and the symptom is a failed login rather than a
configuration error.*

**Seeded user ids are fixed** (`20000000-0000-0000-0000-0000000000NN`), including `testuser` and
`readonlyuser` since 2026-08-12. iam's `V15` seeds credentials keyed on those ids; before they were
fixed, Keycloak generated a fresh id on every import and any seed keyed on them broke silently.

## 7.1 Re-importing the realm

`--import-realm` does **not** overwrite an existing realm. Applying export changes to a running
Keycloak means either recreating the realm or applying them through the admin API.

**The export cannot be imported in one step as written**: it defines the 12-character password
policy *and* seeded users whose passwords predate it (`testpass`), so Keycloak rejects the whole
import with *"Password policy not met"*. Import with `passwordPolicy` removed, then `PUT` it
afterwards. That is not a workaround so much as the normal behaviour — a policy applies when a
password is set, and existing passwords are grandfathered.

# 8. Run topology — a standard, not a preference

| Component | Where |
|---|---|
| the three services | **Docker containers** (`docker-compose.services.yml`, publishing 8092/8093/8094) |
| **Keycloak** | **host** |
| **PostgreSQL** | **host** |
| RabbitMQ, Redis, Elasticsearch, MinIO, MailHog, Jaeger, Prometheus, Grafana, OPA | Docker (`docker-compose.yml`) |

The SPI authenticators call iam **from inside Keycloak's process**, on the host. Docker publishes a
container's port onto the host, so `localhost:8093` resolves. A service started instead as a bare
JVM has **no published port** and the host cannot reach it — not on `localhost`, not on the WSL IP.
Every SPI→iam call then fails and the authenticator fails closed, which reads exactly like an
application bug.

**Therefore: do not use `start-ipie.sh`'s host-JVM mode when testing anything touching the SPI** —
login, credential verification, or pillar SSO. It is the other run mode and competes for the
same ports.

## 8.1 Starting it

From `ipie-platform-mca`, infrastructure first — it creates the network the services join as
external:

```
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.services.yml up -d --build
```

Keycloak, its four SSO mocks and pgAdmin sit behind Compose **profiles**, so the first command
deliberately does not start them. Keycloak belongs on the host (§8); the containerised one publishes
8080 as well and would silently compete with it — two Keycloaks, one port, and whichever loses is
diagnosed as a broken login. Opt in only when you actually want them: `--profile keycloak` for
mock-SSO work, `--profile pgadmin` for the database GUI.

**The two files cannot be merged** with repeated `-f` flags. That collapses them into a single
project, and the services file's `external` network reference then points at itself. They stay two
projects, hence two commands.

Stop in reverse order: services first, then infrastructure.

Containers reach the host via `extra_hosts`: `host.docker.internal:host-gateway` for PostgreSQL and
`keycloak:host-gateway`. **The alias must be exactly `keycloak`** — Keycloak stamps
`http://keycloak:8080` into every token's `iss` and every service validates that exact string.

## 8.2 Shared secrets that have no safe default

| Variable | Set on | Unset ⇒ |
|---|---|---|
| `IPIE_SECURITY_HMAC_KEY_USER_TO_IAM` | services | every user→iam call fails `IllegalArgumentException: Empty key`, HTTP 500 |
| `IPIE_SECURITY_HMAC_KEY_SPI_TO_IAM` | iam | every SPI call to a protected path fails the same way |
| `IPIE_SPI_HMAC_SECRET` | **Keycloak's own process** | as above — this is the signing half and is easy to miss, because it lives outside every compose file |
| `SPRING_RABBITMQ_HOST` | services | events silently downgrade to a logging fallback; the outbox fills and no mail is delivered |

# 9. The shared database, and what it costs

`ipie-user-service` and `ipie-iam-service` share `ipie_user_service`, with iam tracking its
migrations in `flyway_schema_history_iam`.

**Consequence, stated so it is not rediscovered:** `user_credentials.password_hash` and
`credential_setup_tokens` sit in the same database `ipie-user-service` connects to. The ownership
boundary in §3 is therefore **not enforced by the database** — it holds only as long as nobody
writes the join. Any review of a user-service change should ask whether it touches those two tables,
because nothing in the schema will ask.

`V14` ends with a guarded `REVOKE`/`GRANT` for `ipie_user_service_app` / `ipie_iam_service_app`. It
is a **no-op** while both services connect as the `postgres` superuser, which ignores grants — which
is precisely why the migration says so. Creating those roles and connecting as them is what turns it
from documentation into enforcement, and is *not yet done*.

# 10. What is verified, and what is not

**Verified end to end (2026-08-12, real stack):**

- Login works. `testuser` and `creditor.demo` receive tokens, issued off Argon2id hashes in iam's
  `user_credentials`, through the SPI. A wrong password returns `invalid_grant` — not a token, and
  not the *temporarily unavailable* that would mean it was not really checking.
- The registration chain runs. Register with no password → OTP by mail → `complete` returns
  `PROVISIONING` with a null `keycloakUserId` and no synchronous iam call → async provisioning →
  **the set-password email reaches the registrant** → password set → **the new user logs in**.
- The setup token is single-use: replay returns 422.
- The row ends at `UNVERIFIED` with a Keycloak id.
- The pillar-admin approval mail goes to the configured admin mailbox, **not** the registrant.

**Not yet built:**

- A central audit service. `OutboxAuditRecorder` already emits `AUDIT_EVENT` through each service's
  transactional outbox; what is missing is a single consumer. Today each service persists its own
  copy (`audit_trail`, `iam_audit_trail`), joined by `correlation_id`.
- Compliance hardening: hashing `verification_token` and `email_otp_code` at rest (both are stored
  in clear today), outbox PII retention and purge, Aadhaar stored as hash plus masked last-4, and an
  itemised consent record.
- Load testing of the new paths. The `InterServiceClient` bulkhead ceiling remains **reasoned about,
  not measured** — `deploy/jmeter/prepare-registrations.py` exists to build the plan that would
  measure it.
- Migration for any pre-existing Keycloak-held passwords.
- `CredentialService` unit tests.

# 11. Traps that have already cost time

**Recipient addresses in comms are configuration, not code.** `findEmailByPurpose` resolves an
address from the database, so reading the sending code tells you nothing about who receives a
message. Assuming otherwise is what dead-ended the previous design: the whole flow was built,
documented in six places and reviewed, on the belief that "the verification email" reached the
registrant. It goes to a configured pillar-admin mailbox. **Check the configured recipient
before designing a flow around it.**

**Repeated documentation is not verification.** That same wrong claim appeared in six Javadoc
comments and the standards document. Restating an assumption does not test it.

**A repository's `catch (DataIntegrityViolationException)` does not run when Hibernate defers the
insert to flush.** With the transaction committing after the repository method returns, the violation
is raised from the commit - outside the try/catch that looks like it handles the case - and a
duplicate value surfaced as "an unexpected error occurred". Uniqueness conflicts are therefore
translated at the error boundary as well, from constraint declarations each repository publishes as
beans (`IntegrityViolations`, common-libs `persistence`). A declaration held as a private constant is
consulted by nothing but the catch beside it.

**One message for every constraint tells the caller the wrong thing.** The old catches answered "a
user with the same username or email already exists" for a duplicate phone number, a foreign key and
a primary-key collision alike. A violation is now reported by the constraint that actually failed,
and an undeclared one is left as an error to investigate rather than dressed as a 409.

**`CHAR` vs `VARCHAR`.** `token_hash CHAR(64)` is blank-padded by Postgres and reported as `bpchar`;
Hibernate refuses to start against it when the entity maps `varchar`. Use `VARCHAR`.

**Every service defaults to `server.port: 8080`** and collides with host Keycloak if run outside its
container.

**`admin-cli` password grant** works against the `master` realm for the Keycloak admin user; its
tokens are short-lived, so long scripted sequences must re-fetch.

# 12. Where the code is

| Concern | Location |
|---|---|
| Credential store, hashing, endpoints | `ipie-iam-service/src/main/java/in/gov/ipie/service/iam/{security,service,controller,persistence}` |
| Schema and dev seed | `ipie-iam-service/src/main/resources/db/migration/V14`, `V15` |
| SPI authenticators | `ipie-iam-service/keycloak-spi/src/main/java/in/gov/ipie/keycloak/spi/credential/` |
| Registration flow | `ipie-user-service/src/main/java/in/gov/ipie/service/user/service/UserServiceImpl.java` |
| Principal / person / entity split | `ipie-user-service/src/main/resources/db/migration/V13__person_and_entity_principals.sql`, and `persistence/{UserJpaEntity,PersonJpaEntity,OrganisationJpaEntity}.java` |
| Set-password email | `ipie-communication-service/src/main/java/in/gov/ipie/service/communication/service/NotificationServiceImpl.java` |
| Set-password page | `ipie-web/src/pages/SetPasswordPage.tsx` |
| Realm | `ipie-platform-mca/deploy/keycloak/realm-export.json` |
| Run topology | `ipie-platform-mca/docker-compose.services.yml` |
