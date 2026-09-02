# iPIE Architecture Plan — Credentials, Async Coordination, Central Audit

**Created:** 2026-08-11
**Status (2026-08-14):** Stages 1–4 done and proven end to end (16/16 on 2026-08-14). **Stage 5 is all but complete** — items 1, 2, 4, 5 and the retention half of 3 are done and verified; only "slim PII out of event payloads" remains, and it wants Stage 6's shape first. Stage 8's OTP retry cap was pulled forward and is done. Still open: `CredentialService` tests, the rest of Stage 2, Stages 6, 6b, 7, and Stages 8–12 (§6b).

**Resume at:** the comms owner/app role split (small, unblocked), or Stage 6 — which now gates the last Stage 5 item. Open questions 6, 7 and 9 block parts of Stages 8–10; question 8's placeholder consent notice needs replacing before any real user sees it.

**Supersedes:** the uncommitted async-provisioning change described in §1.2

---

## 0. How to resume this work

If you are picking this up in a new session, read in this order:

1. **§1 Current state** — what is on disk right now, including a known-bad uncommitted change and two environment problems.
2. **§2 Decisions** — these came from the user and are not up for re-derivation.
3. **§6 Stages** — the checklist. Find the first unchecked box.

### Local development topology — the standard *for a developer's machine*

**This is where development happens, not where the platform is deployed.** Deployed environments run on Linux servers (§0.1); nothing in the design assumes Windows, and nothing may be written so that it does.

| Component | Where (locally) |
|---|---|
| the three services | **Docker containers** (`docker-compose.services.yml`, publishing 8092/8093/8094) |
| **Keycloak** | **host** — `D:\keycloak-26.6.3`, `kc.bat start-dev --http-host=0.0.0.0`, :8080 |
| **Postgres** | **host** — :5432 |
| everything else (RabbitMQ, Redis, ES, MinIO, MailHog, Jaeger, Prometheus, OPA) | Docker, via `docker-compose.yml` |

**Why the services must be containers here:** the `ipie-keycloak-spi` authenticators call ipie-iam-service from inside Keycloak's process, on the host. Docker publishes a container port onto the host, so `localhost:8093` resolves. A service started as a plain WSL process instead has no published port and the host cannot reach it at all — every SPI→iam call fails and the authenticator fails closed with `503 temporarily_unavailable`, which reads exactly like a code bug. That is what happened on 2026-08-12; the setup was fine, running iam with `./gradlew bootRun` was not.

Corollary: **do not use `start-ipie.sh`'s host-JVM mode when testing anything touching the SPI** (login, credential verify, pillar SSO). It is the other mode and competes for the same ports.

`--http-host=0.0.0.0` matters only on Windows: Keycloak dev mode binds `localhost`, and WSL's `localhost` is a different machine, so a WSL shell cannot reach it otherwise. The SPI is unaffected — it runs inside Keycloak's own process. `start-stack.sh` resolves Keycloak's address rather than assuming it, so it works either way.

**Keycloak must be started with the SPI environment loaded, and this is not optional.** Use `ipie-platform-mca/deploy/keycloak/start-keycloak.ps1`, which loads `deploy/keycloak/env/spi.dev.env`, binds `0.0.0.0`, refuses to start a second instance over the first, and captures the console to a log file — `kc.bat` logs to the console only, so a crash in a closed window leaves nothing to read. By hand it is two steps, in this order:

```powershell
cd D:\keycloak-26.6.3
Get-Content "<repo>\ipie-platform-mca\deploy\keycloak\env\spi.dev.env" |
  Where-Object { $_ -and $_ -notmatch '^\s*#' } |
  ForEach-Object { $n,$v = $_ -split '=',2; Set-Item "Env:$n" $v }
bin\kc.bat start-dev --http-host=0.0.0.0
```

The credential SPI signs its call to iam with a shared key and has **no usable default** for it, deliberately: an empty key fails closed, because the alternative is a login succeeding unverified. Started without those settings, Keycloak answers the token endpoint with `unknown_error` and **iam records no request at all** — the failure is inside Keycloak, before the platform is involved, and it reads exactly like a platform fault. Diagnosed three times on 2026-08-15 before the cause was written down; that is why it is written down here.

### 0.1 Deployed topology, and the configuration contract

Keycloak on the Windows host is a **local convenience**. In `test`, `uat`, `pre-prod` and `prod` it runs on a Linux server, and the SPI's endpoints are that environment's, not a developer's.

The SPI cannot use Spring profiles — it runs inside Keycloak and has no Spring context — so it is configured from **Keycloak's own environment**, one file per environment in `ipie-platform-mca/deploy/keycloak/env/`. `SpiConfig` splits the settings:

| Kind | Settings | Rule |
|---|---|---|
| Platform constants | realm name, SPI client id, HMAC key ids | identical everywhere; defaults apply in all environments |
| Environment facts | every service URL, every secret | defaults apply in **`dev` only**; required elsewhere, enforced at **startup** |

Outside `dev`, a missing setting stops Keycloak from booting with a message naming each one. That is deliberate, and the reason is §8's 2026-08-13 entry: a wrong endpoint is indistinguishable from an outage of the service it points at, so it must not be reachable by inheriting a default. `IPIE_KEYCLOAK_BASE_URL` is still required outside dev even though the answer is always localhost, because the port and scheme are deployment choices.

Secrets appear in `spi.dev.env` only, where they are worthless local-development values; every other file marks them `__FROM_SECRET_STORE__` for the deployment to inject.

**Do not treat `MASTER_CODE_STANDARDS.md` as authority on credentials.** Its "Credential handling (2026-08-11)" section is uncommitted, was self-authored in the same change it justifies, was never reviewed, and contains a factually wrong claim (§1.3). It is a proposal, not a standard.

---

## 1. Current state (2026-08-11)

### 1.1 Repository layout

`/mnt/d/MasterCode` was reorganised on 2026-08-11:

| Path | Contents |
|---|---|
| `IpieMicroservicesCurrent/` | `ipie-platform-mca`, `ipie-iam-service`, `ipie-user-service`, `ipie-communication-service`, `ipie-service-template`, plus `.idea/`, `.ipie-logs/`, `git-history-backup-20260810/` |
| `IpieMicroservicesOld/` | `ipieMaster.zip` (and `ipieMaster/`, once moved) |
| `MasterCode/` (root) | `ipie-web` — deliberately **not** a sibling of the backend repos any more |

Two environment problems are outstanding:

- **`ipie-platform-mca` is unreadable from WSL.** Renaming it while Docker Desktop held bind-mount handles wedged the shared 9p server entry; `ls` shows `d?????????` and `stat` returns ENOENT. **The data is intact** — verified against the Windows filesystem. Until someone runs `wsl --shutdown`, reach it with `"/mnt/c/Program Files/Git/cmd/git.exe" -C 'D:\...'` and PowerShell `Get-Content`/`Set-Content`.
- **`ipieMaster` has not been moved** into `IpieMicroservicesOld/`. Windows refuses while any process holds a working directory inside it - an open shell or a container mount is enough. Move it once nothing is running from there.

### 1.2 Uncommitted work that this plan supersedes

35 uncommitted files across five repos implement an earlier async-provisioning design. **No stashes, nothing unpushed** — the working trees are the only copy.

| Repo | Uncommitted |
|---|---|
| `ipie-user-service` | 10 modified, 7 new + partial edits from this session |
| `ipie-iam-service` | 5 modified, 5 new |
| `ipie-platform-mca` | 5 modified, 1 new (`PasswordPolicy.java`) |
| `ipie-communication-service`, `ipie-service-template` | `MASTER_CODE_STANDARDS.md` only |

**Keep** (independently correct, found by real debugging):

- `KeycloakUserManagementClient.setUserAttribute` — was sending a partial representation to Keycloak's user PUT, which *replaces* rather than patches, wiping email/firstName/lastName/emailVerified on every provisioned account.
- `KeycloakUserManagementAutoConfiguration` — dedicated named `keycloakAdminAuthorizedClientManager` (Boot's default is request-bound and fails off a request thread) plus `@AutoConfiguration(after = OAuth2ClientAutoConfiguration.class)`.
- `createUser` overload that creates an account with **no credentials**.
- `V29__allow_provisioning_registration_status.sql` (already applied to the local DB).
- The event plumbing: queues, bindings, dead-lettering, idempotency on event id.
- `AuditValueMasker` widening, including the reasoned exclusion of `pin` (postal code in this domain).

**Discard or rework** (built on the superseded design):

- `UserService.setPassword` + `RegistrationController` `POST /api/v1/registrations/password` — user-service must not handle credentials at all.
- `AccountProvisioningClient.setAccountPassword` and iam's `PUT /internal/accounts/{id}/password` — a **synchronous** user→iam call on a user-facing path, which is exactly what this plan forbids.
- The single-token design and this session's partial two-token edits in `User`, the JPA entity, mappers, repositories, `V30`, `InvalidPasswordSetupTokenException` — superseded, because credential tokens move to iam.
- Password fields already removed from `CompleteRegistrationRequest`/`Command`/`UserApiMapper` — **that removal stays**, it is correct under this plan too.

### 1.3 The defect that caused the redesign

The superseded design rested on one unverified claim, restated in six Javadocs and the standards doc: *"the user sets their password through the link in the verification email."*

`NotificationServiceImpl.sendVerificationRequest` sends to `recipientRepository.findEmailByPurpose(USER_VERIFICATION_REQUEST)` — a **configured pillar-admin mailbox**, not the registrant. That is the only email carrying a token. The registrant receives `sendRegistrationReceivedNotification`, which has no link. So the flow dead-ended: the account was created without credentials and the user had no way to reach the set-password endpoint.

Reusing one `verificationToken` for both approval and password-setting made it worse — whoever held it could do both.

**Lesson worth keeping:** repeated confident documentation is not verification. Check the recipient of an email before writing about it.

---

## 2. Decisions

From the user, 2026-08-11. Not to be re-litigated.

| # | Decision |
|---|---|
| **D1** | Keycloak issues tokens only. It never stores a password. |
| **D2** | ipie-iam-service is the credential authority: stores hashes, verifies them, enforces policy. |
| **D3** | Communication between services is asynchronous. |
| **D4** | One service holds the audit trail for all services, fed asynchronously. |
| **D5** | Registration collects no password. |
| **D6** | ipie-user-service and ipie-iam-service **keep sharing** the `ipie_user_service` database (user's decision, 2026-08-11, taken with the consequence below stated). Keycloak keeps its own, always. |

**D6's consequence, stated so it is not rediscovered.** `V14` puts `user_credentials.password_hash` and `credential_setup_tokens` in the same database ipie-user-service connects to. The §4.1.1 boundary — iam owns credentials, user-service must never reach one — is therefore **not enforced by the database**. It holds only as long as nobody writes the join. Two things follow:

- The boundary is a convention here, not a constraint. Any review of a user-service change should ask whether it touches those two tables.
- The mitigation that does work is role-level, and it lives in the deployment, not the schema. `V14` ends with a guarded `REVOKE`/`GRANT` block for `ipie_user_service_app` / `ipie_iam_service_app`. It is a **no-op locally**, where both services connect as the `postgres` superuser and grants are ignored. Creating those roles and connecting as them is Stage 5.

**D3 has exactly one deliberate exception**, documented in §4.3: credential verification at login. Authentication is a question with an answer the user is waiting for; there is no asynchronous form of it. Everything else is events.

---

## 3. Verified facts

Everything in this section was checked against the code, not recalled. Where something is *reasoned* rather than measured, it says so.

| Fact | Evidence |
|---|---|
| Keycloak **26.6.3**, pinned | `ipie-iam-service/keycloak-spi/build.gradle`, `ext.keycloakVersion` |
| SPI module exists, jar `ipie-keycloak-spi`, mounted into Keycloak `providers/` | same file, `tasks.named('jar')`; `deploy/keycloak/providers/` |
| SPI deps are all `compileOnly` — no fat jar, no classloader clash | same file |
| Custom `Authenticator` pattern already proven | `PillarLinkResolverAuthenticator` (extends `AbstractIdpAuthenticator`) |
| HMAC-signed SPI→iam calls already proven, byte-for-byte cross-checked against common-libs | `HmacRequestSigner`, `HmacRequestSignerCrossCheckTest` |
| **Browser hook exists**: `AbstractUsernameFormAuthenticator.validatePassword(AuthenticationFlowContext, UserModel, MultivaluedMap<String,String>, boolean)` is `public` | `javap` on `keycloak-services-26.6.3.jar` |
| **Direct-grant hook exists**: `directgrant.ValidatePassword.retrievePassword(AuthenticationFlowContext)` is `protected` | same |
| Realm defines **no** custom `browserFlow` / `directGrantFlow` / `resetCredentialsFlow`; only `ipie-pillar-first-broker-login` | `deploy/keycloak/realm-export.json` |
| ipie-web logs in via **resource-owner password grant** straight to Keycloak's token endpoint | `src/api/keycloakApi.ts`, `grant_type: 'password'` |
| Audit already flows asynchronously: `@Auditable` → `AuditAspect` → `OutboxAuditRecorder` → transactional outbox → broker, as `AUDIT_EVENT` | `ipie-common-libs/.../audit/` |
| Audit is persisted **per service** today: `audit_trail` (user, V16), `iam_audit_trail` (iam, V11); comms has none | migrations; `RabbitUserEventLogConsumer` |
| `correlation_id` is the cross-service join column, by design | `V16` header comment |
| `OutboxAuditRecorder` explicitly leaves central persistence to deployment | its Javadoc |
| `InterServiceClient` Bulkhead: **10 concurrent per target, `max-wait-duration: 0`** — the 11th is rejected, not queued | `ipie-resilience-defaults.yml` |
| Retry: 3 attempts, exponential backoff to 5s; CircuitBreaker: window 10, 50% failure rate, 10s open | same |
| Load testing **found** the `IPIE_SECURITY_HMAC_KEY_USER_TO_IAM` gap — unset, every user→iam call 500s | standards §11 item 6 |
| The bulkhead ceiling is **reasoned about, not measured** — the existing jmeter plan can't reach it because of the per-iteration mail wait | standards §11 item 7 |
| `prepare-registrations.py` exists to build the plan that *would* reach it | `deploy/jmeter/` |
| Realm currently sets `passwordPolicy`, `bruteForceProtected: true`, `failureFactor: 10`, `maxFailureWaitSeconds: 900`, `permanentLockout: false` | uncommitted `realm-export.json` diff |

---

## 4. Target architecture

### 4.0 The services

Each entry says what the service **owns**, what it must **never** hold, and how it talks to the rest. "Never" lines are the ones that decay silently, so they are stated explicitly.

---

**ipie-user-service** — *the person* (port 8092)

Owns the human being as a business fact: the registration wizard and its drafts, registration status, email-OTP verification of the contact address, the pillar-admin approval workflow, profile (name, contact, address, category), identity proof and professional details, organisations, the authoritative `pillar_links`, and people search.

**Never holds:** a password, a password hash, a credential-setup token, or any other credential — in a field, a DTO, an event, or a log. It holds `keycloak_user_id` as a reference only.

**Talks by:** publishing `ACCOUNT_PROVISIONING_REQUESTED`, `USER_REGISTRATION_COMPLETED`, `USER_VERIFIED`, `USER_DEACTIVATED`; consuming `ACCOUNT_PROVISIONED`. No synchronous call to any other iPIE service.

*Misnamed:* by the §4.1.1 boundary this is a profile-and-registration service. The word "user" is what invited credentials into it.

---

**ipie-iam-service** — *the account and what it may do* (port 8093)

Owns identity and access management: account lifecycle in Keycloak (create, enable, disable, delete), credentials (Argon2id hashes, verification, setup/reset/change, policy enforcement), authentication factors and lockout, roles and permissions and their assignment, federation config, the login-path pillar projection, and the Keycloak SPI module.

The only service holding Keycloak admin credentials, and the only one that ever sees a plaintext password.

**Never holds:** profile or PII beyond the identifiers authentication needs. No addresses, no identity-proof numbers, no organisation membership.

**Talks by:** consuming `ACCOUNT_PROVISIONING_REQUESTED`, `USER_VERIFIED`, pillar-link events; publishing `ACCOUNT_PROVISIONED`, `ACCOUNT_CREDENTIAL_SETUP_REQUESTED`. Serves two synchronous internal endpoints — `/internal/credentials/verify` and `/internal/pillar-links/resolve` — **both called by Keycloak's SPI, not by iPIE services** (§4.3).

---

**ipie-communication-service** — *outbound messages* (port 8094)

Owns every message leaving the platform: email and SMS delivery, templates, the per-purpose recipient registry, notification channel preferences, and the notification log with its DPDP masking.

**Never holds:** business state anyone else reads back. It is a sink; nothing queries it to make a decision.

**Talks by:** consuming only. `USER_REGISTRATION_COMPLETED` (admin approval mail + registrant's "received" note), `ACCOUNT_CREDENTIAL_SETUP_REQUESTED` (set-password mail), `REGISTRATION_EMAIL_OTP`, `USER_LOGGED_IN`. Publishes nothing anyone consumes.

*Recipients are configuration, not code.* `findEmailByPurpose` resolves an address from the database — which is precisely why "the verification email" was assumed to reach the registrant when it goes to the admin (§1.3). **Check the purpose's configured recipient before writing about who receives anything.**

---

**ipie-audit-service** — *the record* (new, §4.5)

Owns the audit trail for every service: one append-only store, fed asynchronously by the `AUDIT_EVENT` envelopes already emitted through each service's transactional outbox. Becomes the only queryable audit store; the per-service `audit_trail` / `iam_audit_trail` tables retire.

**Never holds:** anything writable. No UPDATE, no DELETE for the application role. Subject ids pseudonymised so DPDP erasure and CERT-In retention aren't fighting over the same rows.

**Talks by:** consuming `AUDIT_EVENT` from every exchange. Publishes nothing.

---

**ipie-keycloak-spi** — *inside Keycloak's runtime* (`keycloak-spi/` in the iam repo)

Not a Spring service. Ships as `ipie-keycloak-spi.jar` into Keycloak's `providers/`, pinned to Keycloak **26.6.3** exactly. Carries the first-broker-login pillar authenticator, the login-notification event listener, and (Stage 2) the password authenticators that ask iam whether a submitted password is correct.

**Never holds:** state. Every dependency is `compileOnly` so the jar carries none of Keycloak's own classes.

**Configured from Keycloak's environment**, per §0.1 — `SpiConfig` is the single registry of what it reads, and validates it in every factory's `init`, so a misconfigured environment fails at Keycloak startup rather than at the first login.

---

**Keycloak** — *tokens only* (port 8080)

Issues and validates tokens; holds identity attributes, realm roles, and SSO federation. **Holds no password** (D1). Accounts are created with no credentials at all, and credential verification is delegated to iam through the SPI.

---

**ipie-platform-mca** — *shared code and infrastructure*

`ipie-common-libs` (published to `mavenLocal()` — services consume the artifact, not the source), the build conventions, quality config, the Keycloak realm export, the docker-compose infrastructure stack, and the jmeter plans. **No service is built from this repository.**

*After editing common-libs, run `./gradlew publishToMavenLocal` or consuming services will not see the change.*

---

### 4.0.1 API surface, per service

The endpoint inventory, verified against the controllers on 2026-08-13. This is the canonical list —
`MASTER_CODE_STANDARDS.md` governs *how* to build a service and deliberately does not enumerate the
services or their endpoints. Anything under `/internal/**` is service-to-service only: HMAC-signed,
listed in `ipie.security.hmac.protected-paths`, and never reachable from a browser.

**ipie-user-service** (8092)

| Method | Path | Permission |
|---|---|---|
| POST | `/api/v1/registrations` | *public* |
| PATCH | `/api/v1/registrations/{id}` | *public* |
| POST | `/api/v1/registrations/{id}/email-otp` | *public* |
| POST | `/api/v1/registrations/{id}/email-otp/confirm` | *public* |
| POST | `/api/v1/registrations/{id}/complete` | *public* — publishes `ACCOUNT_PROVISIONING_REQUESTED`; rate-limited 5/min |
| GET | `/api/v1/registrations/organisations/search` | *public* |
| GET | `/api/v1/registrations/professional-roles` | *public* — lookup catalogue |
| GET | `/api/v1/registrations/legal-representative-types` | *public* |
| GET | `/api/v1/registrations/professional-identification-types` | *public* |
| GET | `/api/v1/registrations/identity-proof-types` | *public* |
| GET | `/api/v1/users/verify` | *public* — pillar-admin approval link |
| GET | `/api/v1/users/me` | *authenticated* |
| POST | `/api/v1/users` | `USER_WRITE` |
| GET | `/api/v1/users` · `/api/v1/users/cursor` · `/api/v1/users/{id}` | `USER_READ` |
| PUT | `/api/v1/users/{id}` | `USER_WRITE` |
| DELETE | `/api/v1/users/{id}` | `USER_DELETE` |
| POST | `/api/v1/users/{id}/reactivate` | `USER_WRITE` |
| PUT | `/api/v1/users/{id}/organisation` | `USER_WRITE` |
| PATCH | `/api/v1/users/{id}/notification-channels` | `USER_WRITE` |
| POST · GET · PUT | `/api/v1/organisations` (+ `/{id}`) | `ORGANISATION_WRITE` / `ORGANISATION_READ` |
| POST | `/api/v1/pillar-links/initiate` | *authenticated* |
| GET | `/api/v1/pillar-links` · `/callback` | *authenticated* |
| GET | `/api/v1/pillar-links/{userId}` | `USER_READ` |
| POST | `/internal/logins/notify` | `LOGIN_NOTIFY` — called by the SPI's login listener |

**ipie-iam-service** (8093)

| Method | Path | Permission |
|---|---|---|
| POST | `/internal/credentials/verify` | `CREDENTIAL_VERIFY` — **the one synchronous hop** (§4.3), SPI only |
| POST | `/api/v1/credentials/password` | *public* — one-time setup token; rate-limited 5/min |
| POST | `/api/v1/credentials/password/change` | *authenticated* — requires current password; 10/min |
| GET | `/api/v1/roles` · `/api/v1/permissions` | *authenticated* — the catalogue is vocabulary, not a secret |
| POST · PUT · DELETE | `/api/v1/roles` (+ `/{roleId}`) | `RBAC_DEFINE` — SUPER_ADMIN only |
| POST | `/api/v1/permissions` | `RBAC_DEFINE` — SUPER_ADMIN only |
| POST · DELETE · GET | `/api/v1/users/{userId}/roles` | `ROLES_MANAGE` — assignment; PILLAR_ADMIN holds this |
| GET | `/api/v1/users/me/roles` | *authenticated* |
| POST | `/internal/pillar-links/resolve` | `PILLAR_LINK_RESOLVE` — SPI only |
| POST | `/internal/accounts` | `ACCOUNT_PROVISION` — **slated for deletion**, no callers since provisioning became event-driven (Stage 3) |

`RBAC_DEFINE` vs `ROLES_MANAGE` is the create/assign split (2026-08-13): defining what roles and
permissions exist is the platform operator's, assigning an existing role to a user is the admin's.

**ipie-communication-service** (8094)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/v1/notifications` | `NOTIFICATIONS_VIEW` |

Deliberately almost no API: it is a sink driven by events, and nothing queries it to make a decision.

**ipie-audit-service** *(planned, §4.5)* — consumes `AUDIT_EVENT` from every exchange. Its read API
is not designed yet; when it is, it replaces the per-service audit tables as the only queryable
store, and the query surface belongs in this table.

**ipie-keycloak-spi** — no HTTP surface of its own. It runs inside Keycloak and *calls*
`/internal/credentials/verify` and `/internal/pillar-links/resolve`.

---

**ipie-service-template** — *the skeleton.* Clone to start a new service. Not deployed.

**ipie-web** — *the frontend* (port 5173, React/Vite). Talks to user-service for profile and registration, to iam for credentials, and to Keycloak for tokens. Deliberately no longer a sibling of the backend repos on disk (§1.1).

**ipie-config-service** — exists, undifferentiated from the template, not wired into compose or the realm. Decide whether to keep it.

### 4.1 Ownership

| Concern | Owner |
|---|---|
| Token issuance, OIDC, realm roles, SSO federation | Keycloak |
| Password hashes, verification, policy, lockout, credential-setup tokens | **ipie-iam-service** |
| Registration record, profile, PII, organisations | ipie-user-service |
| Email/SMS delivery and notification log | ipie-communication-service |
| Audit trail for every service | **ipie-audit-service** (new, §4.5) |

Keycloak accounts are created with **no credentials at all** — already implemented by the `createUser` overload in §1.2.

### 4.1.1 iam vs user-service — where the line falls

"User data" is three separate things, and only two of them are Identity and Access Management:

1. **The account** — the authentication subject → **iam**
2. **Entitlements** — what that account may do → **iam**
3. **The person** — who they are as a business fact → **user-service**

The third is a business domain that happens to be about people. A postal address or a professional registration number is no more IAM's concern than an invoice is.

**The test for anything new:**

> Could this exist and still be meaningful *before* the person has an account? → **user-service**.
> Is it meaningless without an account? → **iam**.

| ipie-iam-service | ipie-user-service |
|---|---|
| Account lifecycle in Keycloak (create, enable, disable, delete) | Registration wizard, drafts, registration status |
| Credentials: hashes, verify, setup / reset / change, policy | Email-OTP verification of the contact address |
| Authentication factors, lockout | Pillar-admin approval workflow |
| Roles, permissions, assignment | Profile: name, contact, address, category |
| Federation config; login-path pillar projection | Identity proof, professional details |
| The Keycloak SPI | Organisations; authoritative `pillar_links`; people search |

**Two deliberately ambiguous cases, resolved:**

- **Email** is both a contact detail and a login identifier. **user-service is authoritative**; iam/Keycloak holds a login-path projection updated by event — the same pattern `PillarResolution` already uses for stakeholder links. Consequence: a profile email change must be re-verified before it propagates, or the login identifier becomes changeable without proof.
- **Account status.** `UserStatus.ACTIVE/INACTIVE` is a *business* state owned by user-service; Keycloak's `enabled` flag is the *access consequence*, applied by iam on the event. Keep both — collapsing them makes "deactivated for business reasons" and "locked for security reasons" the same field with two meanings.

**Why the two tokens land on opposite sides:** the pillar-admin `verificationToken` authorises a *business decision* (approve this registration), so it stays in user-service; the credential setup token authorises a *credential operation*, so it belongs to iam. The rule predicts the split rather than being fitted to it.

**Naming note:** by this boundary `ipie-user-service` is misnamed — it is a profile-and-registration service, and "user" is the exact word that invites identity concerns to be added to it. Renaming mid-build is probably not worth it; writing the boundary into the standards is.

### 4.2 Registration — no synchronous crossing

```
browser  → user-service  POST /api/v1/registrations/{id}/complete
                         (writes its own row only; no password field)
                         → status PROVISIONING
                         → event ACCOUNT_PROVISIONING_REQUESTED

iam      ← consumes
                         → Keycloak admin: createUser(no credentials)
                         → mints credential-setup token, stores HASH + TTL
                         → event ACCOUNT_CREDENTIAL_SETUP_REQUESTED   → comms
                         → event ACCOUNT_PROVISIONED (no token)       → user-service

user-service ← ACCOUNT_PROVISIONED
                         → stamps keycloakUserId, status UNVERIFIED
                         → event USER_REGISTRATION_COMPLETED

comms    ← ACCOUNT_CREDENTIAL_SETUP_REQUESTED → set-password email to the REGISTRANT
         ← USER_REGISTRATION_COMPLETED        → approval email to the STAKEHOLDER ADMIN
                                              → "registration received" note to the registrant
```

The setup token goes **iam → comms directly** and never passes through user-service. That keeps credentials and credential-adjacent secrets inside the two services that need them.

Two tokens, two recipients, two powers — never one token for both:

| Token | Minted by | Emailed to | Authorises | Consumed by |
|---|---|---|---|---|
| `credential_setup_token` | iam | the registrant | setting the initial password | iam |
| `verification_token` | user-service | the pillar-admin mailbox | approving the registration | user-service |

### 4.3 Login — the one synchronous hop

```
browser → Keycloak token endpoint          (ipie-web unchanged)
        → direct-grant flow
        → IpieValidatePassword (SPI)
        → HMAC-signed POST → iam /internal/credentials/verify
        → Keycloak issues the token
```

**This hop is synchronous and cannot be otherwise.** State it in the standards as a named exception to D3 rather than letting it look like an oversight. What makes it acceptable:

- It is a single hop, Keycloak → iam. It does **not** go through `InterServiceClient`, so it is not subject to the 10-concurrent bulkhead that constrains the user→iam crossing.
- It gets its own timeout and circuit breaker, sized for an interactive auth path (short timeout, fail closed).
- Keycloak's brute-force detector still counts failures, because the authenticator reports `AuthenticationFlowError.INVALID_CREDENTIALS` normally.

The browser (redirect/SSO) flow needs the same treatment via a `UsernamePasswordForm` subclass overriding `validatePassword`.

### 4.4 Credential storage in iam

New tables:

```
user_credentials
  keycloak_user_id  UUID PK
  password_hash     TEXT      -- Argon2id, encoded params inline
  algorithm         VARCHAR   -- so a future rehash-on-login migration is possible
  updated_at        TIMESTAMPTZ

credential_setup_tokens
  token_hash        VARCHAR PK  -- SHA-256 of the token; never the token itself
  keycloak_user_id  UUID
  expires_at        TIMESTAMPTZ
  consumed_at       TIMESTAMPTZ -- single-use
```

**Tokens are stored hashed.** This is a real gap today: `users.verification_token` and `users.email_otp_code` are stored in plaintext, so a DB read, a backup, or a support query yields working secrets. Fix that for the existing columns too (§6, Stage 5).

### 4.5 Central audit service

The async delivery already exists — `OutboxAuditRecorder` puts `AUDIT_EVENT` on the transactional outbox in the same transaction as the business change. What is missing is a single consumer.

**`ipie-audit-service`**: subscribes to `AUDIT_EVENT` from every service's exchange, persists to one append-only `audit_trail`, and becomes the only queryable audit store. Retire `audit_trail` in user-service and `iam_audit_trail` in iam, and the audit half of `RabbitUserEventLogConsumer`.

- Append-only: no UPDATE, no DELETE grants for the application role.
- Indexed on `correlation_id` (the existing cross-service join), plus actor, entity, service, time.
- Retention ≥ 180 days, stored in India (CERT-In).
- Consider a per-partition hash chain for tamper evidence.

### 4.6 The rule, stated precisely

> No synchronous cross-service call on a user-facing path, with one named exception: credential verification at login (§4.3). Reusable credentials never leave iam. Single-use, short-TTL secrets may travel on events, provided they are single-use, short-lived, masked in logs and audit, and stored hashed.

That last clause replaces the absolute rule currently in the standards doc, which is contradicted by shipped code: `RegistrationEmailOtpEvent` already carries an OTP through the outbox to comms, and that is accepted practice.

---

## 5. Statutory constraints

| Requirement | Source | Architectural consequence |
|---|---|---|
| Password hashing | IT Act s.43A + SPDI Rules 2011 (passwords are named sensitive personal data) | Argon2id in iam; never reversible |
| Security safeguards | DPDP Act 2023, s.8(5) | TLS, hashed tokens, least privilege on the audit store — and on the credential tables, which under D6 share a database with a service that has no business reading them |
| Breach notification | DPDP s.8(6) | Central audit + detection must support reconstructing scope |
| **Erasure / storage limitation** | DPDP s.8(7) | **Currently unsatisfiable**: `outbox_events` payloads carry email, name and phone and are append-only. Needs a retention/purge job and slimmer payloads (ids, consumers fetch) |
| 180-day log retention, in India; 6-hour incident reporting; NTP sync to NIC/NPL | CERT-In Directions 2022 | Audit service retention and hosting; clock sync on all hosts |
| Erasure vs retention conflict | DPDP vs CERT-In | Pseudonymised subject ids in audit rows, so retention and erasure aren't fighting over the same data |
| Aadhaar handling | Aadhaar Act s.29 | `users.identity_proof_number` stores raw values today. Aadhaar must be a hash + masked last-4, never clear text |
| Itemised, withdrawable consent | DPDP ss.6–7 | `notificationChannels` needs a consent record: what, when, version, withdrawal |

---

## 6. Implementation stages

Work top to bottom. Each stage should build and its tests pass before the next starts.

### Stage 0 — Environment and hygiene
- [ ] `wsl --shutdown` (user, from Windows), then reopen — unwedges `ipie-platform-mca`
- [ ] Move `ipieMaster` → `IpieMicroservicesOld/` (user, manually, no session inside it)
- [ ] Decide the fate of §1.2's uncommitted work; commit the "keep" list separately from the "discard" list
- [x] Rewrite the standards doc's credential section per §4.6 — do **not** commit the current 48 lines as they stand
      *(2026-08-15: done and committed in all five repos, which keep the file byte-identical. Both sections were still describing the replaced arrangement — passwords travelling to the identity provider, and Keycloak's realm policy as the control with `PasswordPolicy` mirroring it. The §4.6 qualification is now stated: single-use, short-TTL secrets may travel on events when hashed, masked and short-lived, because the absolute rule was one the shipped code already broke. The Keycloak-side paths that can still set a credential outside iam are recorded as **open** rather than described as closed — that is Stage 2's remaining work.)*
- [ ] Move this plan into `ipie-platform-mca/docs/` once WSL can see it

### Stage 1 — iam becomes the credential authority
- [x] `user_credentials` + `credential_setup_tokens` tables (Flyway, iam) — `V14`
- [x] Argon2id hashing (`PasswordHasher`; OWASP params, still un-benchmarked — §7)
- [x] `POST /internal/credentials/verify` — gated by the new `CREDENTIAL_VERIFY` permission
- [x] `POST /api/v1/credentials/password` — public, one-time token, sets initial password
- [x] `POST /api/v1/credentials/password/change` — authenticated, requires current password
- [x] Policy enforcement moves here: `PasswordPolicy` becomes the control, not a mirror
- [x] Rate limiting + `public-paths` (iam had neither before)

### Stage 2 — Keycloak stops holding passwords
- [x] `IpieValidatePassword` direct-grant authenticator (extends `ValidatePassword`; is its own factory)
- [x] `IpieUsernamePasswordForm` browser authenticator (overrides `validatePassword` only, so brute-force/disabled-user handling in `validateUserAndPassword` still runs)
- [x] Register both factories in `META-INF/services`; jar verified — 0 Keycloak classes bundled
- [x] Custom `browserFlow` + `directGrantFlow` in `realm-export.json` (`ipie-browser`, `ipie-direct-grant`, + conditional-OTP sub-flows)
- [ ] Disable `resetCredentialsFlow`, remove `UPDATE_PASSWORD` required action, disable account-console password management
- [ ] Decide where lockout lives (§7); keep Keycloak brute-force counting either way

### Stage 3 — Registration rewiring
- [x] Remove `setPassword` and its endpoint from user-service
- [x] Delete `AccountProvisioningClient` entirely and iam's `PUT /internal/accounts/{id}/password`
- [ ] Delete `POST /internal/accounts` (no callers once provisioning is event-driven)
- [x] iam mints the setup token and publishes `ACCOUNT_CREDENTIAL_SETUP_REQUESTED` → comms
- [x] Revert the two-token edits in user-service (`V30`, `passwordSetupToken`, `InvalidPasswordSetupTokenException`)
- [x] Keep the password-field removal from `CompleteRegistrationRequest`/`Command`/mapper
- [x] comms: set-password email to the registrant, token masked in the notification log, with tests

### Stage 4 — ipie-web
- [ ] Remove the password fields from `RegisterPage`
- [ ] New set-password page + route, posting to **iam**, not user-service
- [ ] Align client-side validation with `PasswordPolicy` (12 chars, not 8)
- [ ] Login untouched — still the Keycloak token endpoint

### Stage 5 — Hardening and compliance
- [x] Create `ipie_user_service_app` / `ipie_iam_service_app` DB roles and connect as them instead of `postgres` — this is what activates `V14`'s grant block and makes D6 survivable
      *(2026-08-13: four roles, not two — each service also gets an `_owner` that runs Flyway, because a runtime connection able to create a table can drop the grants that constrain it. `deploy/postgres/roles/01-create-roles.sql` provisions them; user-service `V30` and iam `V17` grant DML on each service's own tables, by explicit list — "all tables in schema" would hand user-service the credential tables it exists not to see. Verified against the live database: `ipie_user_service_app` is denied on `user_credentials` and `credential_setup_tokens` and allowed on `users`; `ipie_iam_service_app` is the reciprocal; neither can DDL or TRUNCATE. Full E2E still 13/13. **comms done 2026-08-14** — `02-create-service-roles.sql` is **parameterised by service name**, not a second hardcoded file, so any future service that owns its database gets least privilege by running it rather than by someone remembering to extend a list; comms `V12` grants the DML. Doing it surfaced a gap in `01` too: Postgres grants `CONNECT` to `PUBLIC` by default, so before the added `REVOKE CONNECT ... FROM PUBLIC`, `ipie_communication_service_app` could open a connection to `ipie_user_service` and was stopped only at the table. Both scripts now revoke it, and cross-database access is refused at connect time.)*
- [x] Hash `verification_token` and `email_otp_code` at rest; compare by hash
      *(2026-08-13: `V31` renames both columns to `_hash` — a column named `verification_token` holding a digest invites the next reader to compare it against a token. `RegistrationSecretHasher` does peppered HMAC-SHA256, not a bare digest: a six-digit OTP has a million candidates and a plain hash of one is reversed in under a second, so the pepper — absent from any dump — is the whole protection. Constant-time compare. Pepper has a labelled dev default and no default at all under the prod profile. Two things fell out: the OTP was generated with `ThreadLocalRandom`, a predictable PRNG (CWE-338), now `SecureRandom`; and the approval token had to move from `completeRegistration` to `accountProvisioned`, because the event carrying the plaintext is published there — which also means its 48h window now starts when the admin can act on it rather than while provisioning was still in flight. Existing plaintext values are discarded, not migrated: any in-flight approval link must be re-issued. Verified 16/16 end to end, including clicking a real approval link.)*
- [x] Outbox retention/purge job
      *(2026-08-14: `OutboxStore.deletePublishedBefore` + `OutboxRelay.purgePublished`, implemented in all four services' `JpaOutboxStore` and swept daily by each `OutboxRelayScheduler`. Default retention `P30D`, cron and duration both configurable. **Published rows only** — an unpublished row is undelivered work, not history, and ageing one out would drop a business event with no trace; the repository method is named `deleteByPublishedAtNotNullAndPublishedAtBefore` so that rule is visible rather than resting on SQL's three-valued logic. The policy lives in `OutboxRelay` rather than four schedulers so it is written and tested once. Verified live: of three seeded rows — old+published, old+unpublished, recent+published — only the first was removed. Note this is a delivery buffer, not the audit trail: the 180-day CERT-In obligation belongs to the audit record, which is why 30 days is right here.)*
- [ ] Slim PII out of event payloads (ids, consumers fetch) — **the other half of this item, still open.** Touches every producer and consumer, and wants Stage 6's shape settled first: the payload is also what the audit service will persist, so deciding what an event carries and deciding what the audit trail retains are the same decision. Until this lands, DPDP s.8(7) erasure is bounded by retention but not actually satisfiable, because a live 30-day window still contains names, emails and phone numbers.
- [x] Aadhaar: hash + masked last-4 in `identity_proof_number`
      *(2026-08-13: `V33` drops the raw column for a peppered `_hash` (equality checks only) and `_last4` (display). **Applied to every proof type, not only Aadhaar** — a column reversible for PAN and not for Aadhaar is one no reader can reason about, and the branch deciding which is where the mistake gets made; PAN is sensitive personal data under the SPDI Rules anyway. Consequence, deliberate: no full number can be recovered afterwards, so PAN/UIDAI validation (Stage 12) must collect it at that moment rather than read it back — storage limitation working as intended. `last4` is backfilled in SQL; the hash cannot be, since the pepper lives in the application, so pre-existing rows keep a recognisable mask but will not match a duplicate check until re-submitted. The API response field is renamed `identityProofNumberLast4` — a field named for the number while holding a mask is the same lie as the column was — and ipie-web's `CurrentUserResponse` updated to match (nothing read it yet). Verified live: submitting `123456789012` leaves hash + `9012` in the row, zero rows contain the full value, and the response carries only `9012`.)*
- [x] Consent record for `notificationChannels`
      *(2026-08-14: `V34` adds `consent_notices` (one row per notice **version**, never edited in place) and `user_consents` (one row per person per **item** per grant). `users.notification_channels` stays as the working state; these are the evidence beside it, because a column overwritten on every edit destroys the proof DPDP s.6(10) puts on us. Withdrawal is an UPDATE setting `withdrawn_at`, never a DELETE — deleting would destroy the record that consent once existed, and with it any account of why messages went out during that period; a re-grant is a new row, so the history reads grant → withdraw → grant. `ConsentRecorder` works on the difference between two channel sets, so an unchanged channel keeps its original `granted_at` rather than having a decision re-invented for it. Verified live through the real API: registration wrote two itemised rows, withdrawing SMS kept the row and stamped it, re-granting added a third, EMAIL untouched throughout, nothing deleted. **Open question 8 was not answered, so the notice is modelled as a table with a seeded v1 whose text is marked PLACEHOLDER — not legally reviewed, and it must be replaced (or superseded by a v2 pointing at the approved document) before any real user sees it.** Existing users are deliberately not backfilled: inventing a grant nobody made against a notice nobody saw would be false evidence, which is worse than none. Two things noted for later: a read API for "what have I consented to" does not exist yet, and only `notificationChannels` is covered — every other consent-requiring purpose will need its own notice code.)*

### Stage 6 — Central audit service
- [ ] Scaffold `ipie-audit-service` from `ipie-service-template`
- [ ] Consume `AUDIT_EVENT` from every service exchange
- [ ] Append-only `audit_trail`, indexed on `correlation_id`
- [ ] Retire per-service audit tables and the audit half of `RabbitUserEventLogConsumer`
- [ ] Retention policy ≥180 days; pseudonymised subject ids

### Stage 6b — Refresh the ERD and LLD documents
Verified 2026-08-12: **no stale tables exist.** Every table in `ipie_user_service` and
`ipie_communication_service` is entity-mapped, a `@JoinTable` (`role_permissions`, on
`RoleJpaEntity`), or Flyway bookkeeping. The withdrawn `password_setup_token` columns never landed.
Nothing to drop — the work is additive.

- [ ] `ERD_User_IAM_Services.docx` (user + iam repos): add `user_credentials` and
      `credential_setup_tokens`. Confirmed absent. Regenerate from the live schema, which is how the
      current one was produced — there is **no generator script in the repos**, so it must be
      re-introspected.
- [ ] `LLD_IAM_Service.docx`/`.drawio`: add `CredentialService`/`Impl`, `CredentialController`,
      `PasswordHasher`, `CredentialSetupTokens`, the credential domain/repository/persistence
      classes, and the `keycloak-spi/credential` package.
- [ ] `LLD_User_Service.docx`/`.drawio`: remove `AccountProvisioningClient` and `setPassword`, both
      deleted; reflect the asynchronous `completeRegistration`.
- [ ] `LLD_Communication_Service.docx`/`.drawio`: add `sendCredentialSetupLink` and the
      `AccountCredentialSetupRequestedEventConsumer`.

The `.drawio` files are plain uncompressed XML (`agent="ipie-class-diagram-generator"`, ~444
`mxCell` elements) so they are editable; the `.docx` files are text, not exported images
(`LLD_IAM_Service.docx` has zero embedded media), so pandoc can regenerate them.

### Stage 8 — Registration and identity gaps
- [ ] **Mobile OTP**: an SMS channel in comms, `POST /registrations/{id}/mobile-otp` + `/confirm`, `mobile_verified_at` on the user. Reuse `RegistrationSecretHasher` — the code is stored hashed from the start
- [x] **OTP retry cap** — done 2026-08-13 for email; the mobile channel inherits it when it exists.
      *(`V32` adds two counters, because one is not enough: `email_otp_attempts` (guesses against the current code, reset on issue) and `email_otp_resend_count` (codes ever issued, **never** reset). A per-code cap alone is escaped by asking for a new code, walking the space five guesses at a time; the cumulative cap is what bounds it — 5 x 5 means at most 25 guesses against 10⁶, then a human is needed. On exhaustion the code is **discarded**, not the account locked: a timed lockout here would let anyone reaching the endpoint deny service to the real registrant. `@Transactional(noRollbackFor = ...)` is load-bearing — the default rollback-on-RuntimeException would undo the counter increment with the very exception that reports it, leaving a cap that looks implemented and never engages. Verified live: guesses 1-4 rejected, the 5th exhausts and the code is gone from the row, the 6th resend refused. The per-IP limiter masked this in a single-source test, which is exactly why a per-registration cap is needed — a distributed attacker is not slowed by it.)*
- [x] Make OTP expiry configurable rather than the fixed `EMAIL_OTP_TTL` constant — `ipie.registration.email-otp-ttl`, default PT10M
- [ ] **Account type**: Individual vs Entity, and single vs multi-user for entities. Immutable after registration except by an admin action that is itself audited
- [ ] Registration fee trigger on account-type selection — needs a payment service that does not exist; scope it before building
- [ ] Unique human-readable iPIE id: agree the convention, generate on successful registration, immutable
- [ ] Profile update re-verification: a changed mobile or email is re-proved by OTP before it takes effect; PAN re-validated (see §4.1.1's email note)

### Stage 9 — Entity model
- [ ] Entity search by PAN/CIN/LLPIN/TAN as first-class keys, not just name
- [ ] **Hierarchy**: configurable levels (Country/Regional/Zonal/State/Branch), parent-child on `organisations`, visibility cascading upward only, cross-branch isolation by default
- [ ] Entity-admin approval of the entity's own ARs: pending list, approve/reject with remarks, per-AR service permissions, approval email
- [ ] Bulk user creation from Excel: format validation, row-by-row error report, forced mobile-OTP first login, notification fan-out

### Stage 10 — Access control at scale

Reconciled against the FRS (`iPIE RFP/IPie FRS.pdf`, User Management items 8–15) on 2026-08-15. The
requirement numbers below are that document's.

#### 10.1 The model

**Only roles are assigned to a user. Permissions are never granted or removed individually.** A
person needing a different set gets a different role; if none fits, one is defined. That is the
decision (user, 2026-08-15), and it is worth stating why it matters technically: Keycloak realm roles
are additive, with no negative role, so the existing `realm-roles-to-permissions-claim` mapper
continues to work and the `permissions` claim stays derivable. The moment a single permission can be
subtracted from one person, that mapper cannot express it, and the platform needs a custom protocol
mapper backed by iam plus a per-request lookup. Additive-only buys that back.

Effective permissions resolve as one union, with no precedence rules to get wrong:

```
permissions(user) = ⋃ permissions(role)
                    for role ∈ roles assigned directly ∪ roles assigned to the user's groups
```

**Pillars and stakeholders are different things, and the platform now uses each word for one of
them only** (programme, 2026-08-17).

| | **Pillar** | **Pillar** |
|---|---|---|
| Who | IBBI, NCLT, NCLAT, MCA, NeSL | a *user of iPIE* under the umbrella of a pillar, an IP/IPE or an entity |
| What it is | an institution the platform is built around and federates identity with | a person participating in the process — related to an IP, a financial or operational creditor, or a corporate debtor |
| In the code | `PillarType`'s five values; the external identity a `pillar_link` points at | the `STAKEHOLDER` base role every verified user holds |
| Administered by | `PILLAR_ADMIN`, scoped to that pillar | whichever tier they sit under — pillar, IP/IPE or entity |

The FRS treats stakeholders as a domain of their own, with a **separate stakeholder-management
microservice** for users related to the IP, FC, OC and CD. That is why the word could not also mean
"one of the five institutions": it is already spoken for, and the service that will own it does not
exist yet to defend the meaning.

`STAKEHOLDER_ADMIN` was therefore renamed to **`PILLAR_ADMIN`** (iam `V20`). The old name said the
opposite of what it meant — it administers a pillar, not stakeholders — while `STAKEHOLDER`, one
underscore away, genuinely does mean a stakeholder. Two roles that close together pointing at
unrelated tiers is the kind of thing that reads correctly and is understood backwards.

**Naming rejected on the way, and why it is worth not revisiting.** `PARTNER_ADMIN` was considered
and dropped: `LegalConstitution.PARTNERSHIP` already exists and an IPE is a firm whose members are
partners, so the name would most naturally read as *admin over an IPE's partners* — the tier
immediately below the one it names. A name that points at the wrong rung of the ladder it belongs to
is worse than one that is merely vague.

Three granting scopes, not two. The minutes of 22 July and 13 July make the third one unavoidable:

| | IP / IPE — an individual or professional entity | Entity — an organisation | Case |
|---|---|---|---|
| Who sits under it | stakeholders, representatives (AR), lawyers, forensic auditors | employees and authorised representatives | whoever is party to that assignment |
| How rights are given | a role per individual | roles to a **group**; individuals outside groups get roles directly | a role **on that case only** |
| Structure | flat, scoped to that IP/IPE | hierarchy, FRS item 9 | per assignment |

**Two different things are called a "role", and conflating them will cost a rewrite.**

| | What it is | Where it lives | Example |
|---|---|---|---|
| **Professional role** (qualification) | what a person *is*, proved by a credential | `user_professional_roles`, set at registration | Insolvency Professional, Registered Valuer, Legal Representative |
| **Capacity on a case** (assignment) | what a person *acts as* on one assignment | the case model, per `case_id` | IRP on case A, RP on case B, Liquidator on case C, AR on case D |

The programme stated it plainly on 2026-08-15: *an IP has a single login, but the role changes by the
case id, that is, the assignment*. IBBI said the same on 13 July - one account covers IRP, RP,
Liquidator and AR - and those four are capacities, not qualifications: nobody registers as "an IRP",
they are appointed as one on a particular matter.

So `V5` has settled the qualification half. **The capacity half is not built and cannot be until a
case or assignment model exists**, which is why it belongs here rather than in Stage 8. It also
explains the conflict rule below: "the same individual may not be IP and RV on the same case"
constrains capacities on one case, and is unrepresentable in a model that only knows what a person
is.

**A super admin governs pillar admins; it is not one** (programme, 2026-08-17). `SUPER_ADMIN`'s
realm role used to composite `PILLAR_ADMIN`, so a platform operator literally held the pillar
administrator's role. Authority and containment are different relationships, and the composite
asserted the wrong one. It matters more the moment `PILLAR_ADMIN` is scoped: a pillar admin belongs
to *one* pillar, so a super admin holding that role would have to be scoped to a pillar too, which is
exactly what a platform operator is not.

Nothing was lost by removing it — `PILLAR_ADMIN` contributed only `ROLES_MANAGE`, which `SUPER_ADMIN`
already grants directly. Control is expressed through the delegation ceiling instead: a super admin
holds every permission `PILLAR_ADMIN` grants, so it may create, assign and revoke that role for
anyone, while a pillar admin cannot reach above itself. Verified both directions.

**Two axes place a principal, both optional** (programme, 2026-08-17; built in user-service `V9`).
Most principals sit on one axis and not the other, which is why neither is mandatory:

| | Hierarchy — `users.organisation_id` + `organisations.parent_id` | Scope — `users.pillar_scope` |
|---|---|---|
| Answers | who a user sits *under* | which pillar *validates* them |
| Insolvency professional | null — sits in no hierarchy | `IBBI`, by registration number |
| Entity admin | root of their own tree | null — no pillar validated them |
| Entity's invited users | the entity node | null |

**An IPE is itself an insolvency professional** (programme, 2026-08-17). More than one IP together
form an Insolvency Professional Entity, and the IPE holds the qualification in its own right, with
its own IBBI recognition number. The model could not say so: `user_professional_roles` has a
`user_id` and had no counterpart, so a qualification was something only a person could hold, and an
organisation was a container for principals rather than a principal itself.

`organisations.pillar_scope` and `organisation_professional_roles` (user-service `V11`) fix that,
mirroring the user side rather than adding an `is_ipe` flag — a boolean would record that the entity
is an IP and lose the registration number that proves it, which is the mistake `V5` corrected on the
user side.

**This is the case that decides union over intersection.** A member IP carries *both* axes: the IPE
as their hierarchy node and IBBI as their own scope, because an individual registration does not
lapse on joining a firm. Intersecting the axes would make that member visible only to someone
holding both, and would hide a sole practitioner — an organisation of one — from everybody. Verified:
after the two seeded IPs gained the IPE as their node, the IBBI pillar admin still saw them.

A consequence for the case model, worth recording before it is built: **a capacity may be held by an
organisation, not only by a person**, since an IPE that is an IP can be appointed. `IRP`, `RP` and
the rest therefore need a holder that may be either, which is a different shape from assuming a user
id on the assignment row.

**The umbrella is always an organisation, never a user.** An IP who administers people under their
practice needs something a row can point at, and an IPE already is an organisation while a sole
practitioner is an organisation of one. A parent pointer that was sometimes a user and sometimes an
entity is a foreign key no database can express and a branch every query would carry.

**Visibility is the union of the two axes, plus the caller's own record.** A super admin is
unrestricted. A pillar admin sees every principal their pillar validated, wherever they sit. An
entity or IP admin sees the subtree rooted at their own node — so a parent sees all its children and
**two children of one parent see nothing of each other**, because neither subtree contains the other.
Everyone else sees exactly one record: their own.

Union rather than intersection, deliberately: an insolvency professional carries a pillar and no
organisation, so intersecting the axes would hide precisely the population a pillar admin exists to
administer.

**Enforced as a `WHERE` clause in both search backends.** `UserSpecifications.visibleTo` for
Postgres and a mandatory `filter` clause for Elasticsearch, with the scope in `UserSearchIndex`'s
signature so a second backend cannot quietly omit it — the Elasticsearch implementation would
otherwise have answered every search with every user while the Postgres one stayed correct, a
divergence nothing would report since both return a well-formed page. Filtering after the fetch was
never an option: it returns short pages and reports totals that count rows the caller may not see
(10.4).

**The tier did not work until `PILLAR_ADMIN` gained `USER_READ`** (iam `V23`). `GET /api/v1/users`
is gated on it, and it existed only as a realm role — absent from this service's catalogue, so no
role defined here could grant it. The endpoint answered 403 before any visibility rule was consulted.
`USER_READ` only: seeing the users of your pillar is what was asked for, and the delegation ceiling
means whatever `PILLAR_ADMIN` holds is also what a pillar admin can hand onward.

**Still unbuilt:** the entity/IP admin *role*. The hierarchy axis is implemented, unit-tested and
verified against real data, but there is no role that makes someone an entity administrator, so the
axis is exercised through the resolver rather than over HTTP. That is 10.1's "one scoped role, not a
role per level" and it needs `roles.scope_type`/`scope_id`.

**Four administrative tiers, one rule.** Described by the programme on 2026-08-17: a super admin
holds every power; a pillar admin a subset, over their own pillar's users; an entity admin over its
own organisation subtree; and an IP over the individuals under their umbrella. They are not four
mechanisms but one — *a grantor may assign a subset of their own permissions, within their own
scope* — differing only in scope:

| Tier | Scope | Status |
|---|---|---|
| Super admin | platform | role and ceiling both in force |
| Pillar admin (IBBI/NCLT/NCLAT/MCA/NeSL) | one pillar | ceiling in force; **scope not built** |
| Entity admin | own org subtree | not built — needs `organisations.parent_id` |
| IP as admin | own umbrella | not built |

The permission half is enforced today; the scope half is not, so `ROLES_MANAGE` remains
platform-wide in reach even though it is now bounded in power. `organisations` has neither
`parent_id` nor a level column and holds no rows, so there is no pillar record for an admin to be
scoped *to* — that is what 10.2's `user_roles.scope_id` is for.

**Case-data permissions never hang off a qualification** (iam `V22`, 2026-08-17). A qualification
says what a person *is* and is scoped to no case, so `CLAIMS_READ` on `INSOLVENCY_PROFESSIONAL`
granted claim access on every case in the platform, including those where the holder acts in no
capacity. `CLAIMS_*` were stripped from `INSOLVENCY_PROFESSIONAL` and `CREDITOR`; both keep
`DASHBOARD_VIEW` alone. They belong to capacities, which exist only as case-scoped rows for a bounded
interval.

**`CLAIMS_WRITE` was two powers under one name**, and is now `CLAIMS_FILE` and `CLAIMS_VERIFY`.
Filing is the act that *creates* the creditor relationship, so it cannot be gated on already being a
party — gate it and nobody can file the first claim. Verifying is the resolution professional's
power and the act that decides financial versus operational. One permission covering both let anyone
who may file also adjudicate.

**`CREDITOR` is inert, not deleted.** Creditor status is per-case, per-organisation and adjudicated —
derived from an admitted claim, not held as a role — so the role has no future in this table.
Removing it belongs with the case model that replaces it; deleting it today would orphan a seeded
assignment while leaving nothing able to express what it meant.

**A role assignment may be global or case-scoped.** The IPs asked to "create or manage users
associated with a case", "control access to case-specific information", and for lawyers to have
case-specific access rather than a full login (22 July, §5 and §17). A person is therefore not simply
"an AR" — they are an AR *on this case*, and hold nothing on any other. `user_roles` needs an
optional case reference, and every resolution must be evaluated in the context of the case being
accessed.

**The capacity lifecycle, as the programme described it on 2026-08-15.** NCLT admits a petition, the
company enters CIRP, and an IP takes it as IRP. The CoC then either confirms that person as RP or
recommends a different IP, who replaces them. Later the matter may go to liquidation. The four
capacities — IRP, RP, Liquidator, AR — are all held at case level, and one person carries different
ones on different cases at the same time.

Three properties follow, and each of them constrains the schema:

- **A capacity is an interval, not a value.** Case A's IP was IRP from admission until the CoC
  confirmed them, and RP after. Both remain true of their own periods: an order signed during the
  IRP window was signed by the IRP, and an audit years later must still say so. So an assignment row
  needs `effective_from` and `effective_to`; a transition closes one row and opens the next. A
  mutable `capacity` column answers "what are they now" and destroys every other question.
- **Replacement is the same operation as transition, with a different person.** IRP → RP may keep
  the incumbent or install a new IP on CoC recommendation. Either way the outgoing row closes and a
  new one opens; the outgoing person keeps their history on that case and loses access to it, while
  their other cases are untouched. This is why authorization has to be evaluated at a point in time,
  not merely per case.
- **Exclusivity is per case, and it is the person that is excluded, not the capability.** An IP who
  is RP on a case can never be its Liquidator, and cannot be AR on it either; the same individual may
  hold Liquidator on a different case they have no part in. This extends the rule IBBI gave on
  13 July that one individual may not be IP and Registered Valuer on the same case. Confirmed by the
  programme on 2026-08-16: the older position under IBC s.34(1) — the RP continuing as liquidator
  unless the Adjudicating Authority directed otherwise — is **no longer the standard**, and must not
  be reintroduced by anyone reading the bare section. The constraint is therefore evaluated against
  `(case, person)` and never against the person alone, which is exactly the evaluation a model
  holding only what a person *is* cannot perform.

**"AR" is two different things, and only one of them is an IP.** Settled by the programme on
2026-08-16, and the distinction decides the constraint:

| | Class AR | Entity AR |
|---|---|---|
| Represents | a class of creditors — homebuyers, a bond-holder class | one creditor entity, e.g. a bank |
| Is an IP | **yes** — the qualification is a precondition of the appointment | **no**, ordinarily not |
| Authority rests on | their IBBI registration, plus selection by the class | an authorisation letter issued by the entity they represent |
| Appointed by | the process, from the class | the creditor entity itself, not the IP |
| How many per case | one **per class**, so several at once | one per entity, so several at once |

So the uniqueness constraint is neither `(case, capacity)` nor a single `(case, capacity, class)`.
A class AR is unique on `(case, class)`; an entity AR is unique on `(case, creditor_entity)`. They
are better modelled as two capacities — `AR_CLASS` and `AR_ENTITY` — than as one capacity with a
nullable class, because their **admissibility rules differ**: assigning a class AR must assert the
person holds `INSOLVENCY_PROFESSIONAL`, while assigning an entity AR must assert a verified
authorisation letter instead. One capacity with two mutually exclusive preconditions is a rule
nobody will read correctly at the call site.

Two consequences worth stating:

- **The authorisation letter is evidence of an appointment, not a credential of a person.** It
  belongs on the assignment row, with the entity that issued it, not in `user_professional_roles` —
  which holds registrations that prove what someone *is*. This is also why `AUTHORIZED_REPRESENTATIVE`
  does not belong in the professional-role catalogue, and why the multi-qualification demo account
  deliberately does not hold it.
- **There is nowhere to put that evidence today.** The registration form already offers "Click to
  upload Authorization Letter scan", but `UploadDropzone` is UI-only by design — it validates type
  and size client-side and never uploads anything, because document storage
  (`ipie-common-file-storage`) was out of scope for that pass. So the field exists, collects nothing,
  and a reviewer would reasonably assume a letter had been captured.

**Open:** whether an entity AR's authorisation is per case or standing across an entity's cases. A
bank's nodal officer is plausibly the latter, which would put the letter on the entity relationship
and only the appointment on the case.

**A representative is always bound to a principal, and the principal is never the IP.** Generalised
by the programme on 2026-08-16: an AR is never free-standing — they carry someone's interest. What
that resolves is *whose*.

| Capacity | Principal | What they represent |
|---|---|---|
| IRP / RP / Liquidator | none | nobody — an officer of the process itself |
| `AR_CLASS` | the class of creditors | that class's collective stake |
| `AR_ENTITY` | one creditor entity | that entity's stake |

So a capacity row carries an optional `principal_type` / `principal_id`, polymorphic over a class and
a creditor entity, and null for the officers.

**The principal of a class AR is the class, not the IP that listed them.** The IRP supplies the panel
of names; the class selects from it. Recording the IP as the principal would be wrong in three ways
that all surface later: replacing the RP would look as though it should cascade to the AR; the AR
would appear to owe a duty to the RP, when in CoC voting they exist precisely as a check on the RP;
and the AR's visibility would be derived from the RP's, which is far wider than the class is entitled
to.

**Visibility is derived from the principal, not granted to the representative.** An AR's access is
the *intersection* of the case scope and what their principal may see — a homebuyer-class AR sees
what that class sees, a bank's AR sees what that bank sees. Granting "the AR can see the case"
instead leaks one creditor's position to another creditor's representative, which is a disclosure
failure rather than a bug in a filter.

That derivation has to be evaluated live, never copied at appointment time. A claim rejected, or a
bank assigning its debt away, changes what the principal is entitled to; an entitlement snapshotted
onto the representative's row outlives the stake it came from and keeps working.

**Where this generalisation stops.** The umbrella relationship above — an IP's subordinates and
facilitators — is also "bound to someone", and is *not* the same edge. Those people act **for** the
IP within the IP's own work; an AR acts **against** the RP's position whenever the class requires it.
Same shape, opposite direction of interest. One table for both would put a delegate and an adversary
in identical rows and invite a policy that treats them alike, which is how a representative ends up
inheriting the access of the person they exist to scrutinise.

**Delegated administration is one scoped role, not a role per level.** The programme's shape is a
super admin on top, an entity admin beneath, and an IP administering the people under their own
umbrella. Those are the same role held at different scopes — not three roles — and building them as
three global roles produces a role per organisation per level within a year. `roles.scope_type` and
`scope_id` (10.2) are what make one definition serve all three; SUPER_ADMIN is simply that role at
`PLATFORM` scope.

The umbrella has to be something a row can point at. Modelling an IP's practice as an organisation
node — an IPE already is one, and a sole practitioner is an organisation of one — lets the hierarchy
in 10.2 serve both cases, rather than the platform growing a second bespoke "sponsored by" edge
beside `organisations.parent_id`.

**Users hold roles; users never hold permissions.** The request that prompted this asked for an
admin who could assign or revoke *permissions* for a user. That is the one part to refuse, and the
programme said so itself earlier: only roles are added or removed for a user. Per-user permissions
destroy the question an audit exists to answer — "who can approve a claim?" stops being "holders of
these roles" and becomes a scan of every user's individual grants, which no one can review and no
report can summarise. The real need behind the request — an AR who should do slightly less than the
standard AR — is served by letting a scoped admin *define a role inside their own scope*, composed
only from permissions they themselves hold. Flexibility is preserved; the role stays the unit of
grant and review.

Both subset rules in 10.3 — delegation ceiling and scope containment — are load-bearing here rather
than optional hardening. Without the first, the IP admin's first act can be to grant themselves
SUPER_ADMIN, and the system is working as designed while they do it.

Finally, **the umbrella scope is not the case scope**. Someone in an IP's umbrella may hold no case
capacity at all, and an AR appointed on a case need not be in anyone's umbrella. Two separate edges;
collapsing them repeats the qualification/capacity conflation in a new place.

#### 10.2 Schema

**Revised 2026-08-17 from the programme's own sketch.** What follows replaces the earlier assumption
that an organisation is only a container for people. The change is not cosmetic: it decides what a
foreign key can point at, and two tables already built are superseded by it (noted at the end).

**Every principal is a user row.** A person is one; an entity is one. *"Each entity creates a user
irrespective of the authorised representative of that entity, so two users get created for an
entity"* - the entity's own principal, and the person authorised to act for it. An IPE is therefore
a principal in exactly the sense an IP is, which is what makes "an IPE is considered an IP"
expressible without a second parallel vocabulary.

The alternative - giving organisations a separate login namespace - was rejected. It would make
"principal" two types everywhere downstream, including on the capacity holder, which we have already
established may be an organisation.

| Table | Holds | Notes |
|---|---|---|
| `users` | every principal, person or entity | `is_org` discriminator, plus `pillar_scope` (built, `V9`) |
| `person` | person detail: name, contact, identity proof | `user_id` `UNIQUE NOT NULL` → `users` |
| `organisations` | entity detail: constitution, CIN, address | `user_id` `UNIQUE NOT NULL` → `users` |
| `org_partners` | `(organisation_id, user_id)` | membership: an IPE's partners, an entity's employees |
| `user_hierarchy` | `(user_id, parent_user_id)` | person under person: the ARs and facilitators an IP invites |
| `ip_afa` | `(user_id, afa_id, valid_from, valid_to, issued_by_*)` | Authorisation for Assignment - see below |

**Two edges, not one tree.** Membership of an entity and delegation between people are different
relationships and are modelled separately. The earlier plan collapsed both into
`organisations.parent_id` by treating a sole practitioner as "an organisation of one"; testing that
showed the convenience costs more than it saves, because it forces a synthetic organisation row for
every practitioner who invites anyone - rows the domain never asked for, existing only to satisfy a
pointer. The cost of the split is honest and worth naming: "may A see B" becomes a union of two
walks rather than one recursive CTE, and a cycle can now form *across* the two relations rather than
only within one, so both need a guard.

**- [ ] AFA (Authorisation for Assignment), and it is missing entirely today.** An IP may not take an
      assignment on the strength of their qualification alone; they need a valid AFA, issued for a
      period and capable of lapsing. The word appears nowhere in this plan or the code, which means
      any appointment check written today would ask the wrong question - "does this person hold the
      IP qualification" rather than **"did they hold a valid AFA on the date of appointment"**. A
      qualification is a standing fact; an AFA is an interval, the same distinction 10.1 already
      draws for capacities, applied one level earlier.
      The check must be *as at* a date rather than "is currently valid", or an audit years later
      cannot reproduce why an appointment was permitted.
      **The issuer is two different things** - the pillar for an IP in their own right, the IPE for
      an IP practising through an entity. Recorded as two typed nullable columns with a check that
      exactly one is set, rather than one polymorphic `reference` column: a single column meaning
      different things by row admits no foreign key and obliges every reader to know the rule.

**- [ ] Keys are surrogate ids, never usernames.** The sketch keyed relationships on
      `ORG_USERNAME`/`IP_USERNAME`; the programme has since agreed these become ids. Worth recording
      why, because the temptation returns: `username` and `email` are currently identical in all 39
      seeded rows, so a username join key is an email address, and an email changes. A natural key
      that mutates drags every referencing row with it. Username stays a unique attribute of the
      principal and nothing points at it.

**What this supersedes in what is already built:**

- `organisation_professional_roles` (user-service `V11`) becomes unnecessary. **Done in `V13`** -
  its rows moved to `user_professional_roles` against each entity's new principal and the table was
  dropped, after widening the value column to the 100 characters V11 had declared. It exists so an
  organisation can hold the IP qualification; once the entity has its own user row, that qualification
  belongs in `user_professional_roles` against it, using one table for both kinds of principal. The
  table should be folded back rather than left as a second way to say the same thing.
- `users.organisation_id` and `organisations.parent_id` (`V9`) give way to `org_partners` and
  `user_hierarchy`. `pillar_scope` and the visibility union are unaffected and stand.
- `VisibilityScope`'s hierarchy axis keeps its shape - self, plus pillar, plus descendants - but
  resolves descendants from the two edges instead of one organisation subtree, and must now follow a
  DAG rather than a tree: the same principal can be reached by more than one path, so the walk needs
  a visited set it did not need before.

**- [ ] Retention and partitioning, decided before the data exists.** `audit_trail` grows forever
      and `outbox_events` keeps every published row; neither has archival and both are write-heavy.
      Monthly partitioning on `audit_trail` and deletion of published outbox rows are the standard
      answers, and both are far cheaper to introduce now than to retrofit onto a large table that
      cannot be locked. Nothing in this plan addressed either until 2026-08-17.

**- [ ] Index for `status`, not merely on it.** `status = 'ACTIVE'` will match almost every row, so a
      plain index on the column is dead weight that only slows writes - `idx_users_status` already is.
      When the shared status set lands, the hot indexes should become partial (`WHERE status =
      'ACTIVE'`) or carry status as a trailing column, or the change buys write cost and no read.

**- [ ] Every table carries a `status`** (programme, 2026-08-17). One place to add it:
      `AuditableJpaEntity`, the `@MappedSuperclass` every service's entities already extend for the
      audit and soft-delete columns, so no entity declares it by hand and no table can be missed.

      **It must replace `is_active`, not sit beside it.** Eight user-service tables and three of
      iam's carry `is_active` today, plus `deleted_at`/`deleted_by`. Adding a status alongside gives
      three overlapping ways to say whether a row is live, and they will disagree - a row updated
      through one path and not the other is a bug nothing reports, because each column is
      individually plausible. The migration should derive the initial status from `is_active` and
      `deleted_at` and drop the boolean in the same change.

      **Two lifecycles are in play and one enum may not serve both.** For a principal, status is a
      domain fact with real states - the minutes call for IBBI-instructed account **suspension**,
      which is the evidence that restrictions are account-level rather than permission-level (10.6).
      For a lookup row it means something narrower: `professional_roles.is_active` currently means
      "offered in the registration wizard", which is how `V7` retired ten codes without deleting
      declarations that referenced them. Collapsing "a suspended user" and "a retired lookup value"
      into one vocabulary would make `ACTIVE` the only word that means the same thing in both.
      **Decided 2026-08-17: one shared minimal set, which a table may extend.** Every table
      guarantees `ACTIVE`, `INACTIVE` and `DELETED`; a table adds its own where the domain needs
      more - `SUSPENDED` for a principal, `RETIRED` for a lookup value whose meaning is "no longer
      offered". Anything reading a row it does not own can rely on the three and ignore the rest,
      which is what makes the set shared rather than merely conventional.

      The codebase already has this shape and it should be reused rather than reinvented:
      `ErrorCode` is an interface in `ipie-common-libs` that per-service enums implement
      (`RoleErrorCode`, `StakeholderLinkErrorCode`). A `RecordStatus` interface with the three
      constants, implemented by a per-entity enum, gives the same shared-contract-with-local-values
      arrangement. The column stays a `VARCHAR` with a per-table `CHECK` naming exactly the values
      that table permits, so the database rejects a value the enum does not know rather than storing
      it and failing on read - the failure mode V10's `PRIVATE_LIMITED` produced.

      A Java enum cannot extend another, which is why this is an interface and not a base enum; the
      mapped superclass therefore holds the column and each entity exposes its own typed accessor.

      Note that `users` already has two status-shaped columns - `status` and `registration_status` -
      and they answer different questions ("may this account be used" versus "how far through
      onboarding"). Whatever set is chosen must not quietly absorb the second.

**Settled 2026-08-17.** The three questions above were answered by the programme, and each answer
constrains the schema rather than merely describing intent.

**1. `users` becomes a principal table; detail moves out.** **Built 2026-08-18 as user-service
`V13__person_and_entity_principals`** - `person` created and backfilled, `organisations.user_id`
added with a principal per entity, `is_org` on `users`, twelve detail columns dropped, and
`organisation_professional_roles` folded back into `user_professional_roles`. The invariant is
enforced in `OrganisationJpaEntity`'s constructor and checked at the end of the migration; the two
edges (`org_partners`, `user_hierarchy`) and `ip_afa` are deliberately not part of it. `person` and
`organisations` hold the detail. An entity's principal therefore stops pretending to have a full name, a phone number and an
identity proof, and a person's stops pretending to have a CIN.

This also answers the normalisation objection raised the same day. `users` is 37 columns because it
holds four unrelated things at once: the principal, the person, the postal address, and the transient
registration workflow. Splitting person detail out is the first of those cuts and the one the rest
depend on - the OTP and verification columns can then move to a registration table without disturbing
identity, and the address becomes a shared type rather than being spelled two different ways in
`users` and `organisations`.

**The reference points from the detail to the principal** (decided 2026-08-17). `person.user_id` and
`organisations.user_id`, each `UNIQUE NOT NULL` and each a foreign key to `users`. The earlier sketch
had `users.ref_id` pointing the other way with `is_org` saying which table it meant; that direction
admits no foreign key at all, because one column cannot reference two tables. Reversed, the database
enforces both that every detail row has a principal and that no two claim the same one, and `ref_id`
disappears.

`is_org` stays on `users` as the discriminator, and is worth understanding for what it now is: a
cached copy of a fact that has become derivable - which detail table holds the row. It earns its
place because "is this principal an entity" is asked on every authorisation path and should not cost
a join, but it is denormalised and can therefore disagree with reality. **It must be written in the
same transaction as the detail row**, and the pairing - `is_org` true with an `organisations` row,
false with a `person` row - is a cross-table invariant no plain constraint can express. Enforce it
where the write happens, and check it in the migration that creates these tables rather than assuming
it; a principal with `is_org` false and no `person` row is invisible to every screen that reads a
name, which reads as missing data rather than as a broken invariant.

**2. One entity at a time, and the AFA belongs to the person.** A person must be delinked from one
entity before being linked to another, so `org_partners` is unique on the person **among rows still
open** - a partial unique index, not a plain one, because delinking closes a row rather than deleting
it (10.3: "assignment closes, it does not delete"). Membership therefore needs `valid_from`/
`valid_to` of its own, and "who were the partners of this IPE in March" stays answerable.

The AFA **stays with the person** across that move. It is their authorisation, not the practice's, so
delinking from an IPE does not end it and joining another does not confer one. The issuer reference
records the route by which it was obtained - directly with the pillar, or through an IPE - and is
history rather than a live pointer: it must not be rewritten when the person moves.

**3. `user_hierarchy` is a DAG, not a tree.** A person may sit under more than one parent - a
facilitator serving two IPs is the ordinary case - so the primary key is `(user_id, parent_user_id)`
and never `user_id` alone. Two consequences follow:

- **A cycle guard is required**, and a single-row check cannot provide it. With one parent a cycle
  needs a walk to detect; with many, the graph can close through any of several paths, so the check
  belongs where the whole chain is visible, on write.
- **The two edges now behave differently on purpose**, and that asymmetry is easy to misread as an
  oversight: entity membership is exclusive and time-bounded, person-to-person delegation is
  many-to-many. Anyone generalising "a principal's parents" across both will get one of them wrong.


- [ ] `organisations.parent_id` plus a level (`COUNTRY`/`REGIONAL`/`ZONAL`/`STATE`/`BRANCH`) — FRS
      item 9. Visibility cascades **upward only and along direct ancestry**: the FRS says a higher
      level sees what a lower level sees "if it falls in direct hierarchical structure", which
      excludes siblings.
- [ ] `roles.scope_type` (`PLATFORM` | `IP` | `ORG`) and `roles.scope_id`. Roles are global today —
      nine of them. Once every IP and entity can define one, a single catalogue would offer every
      IP's variants to every other IP. A role may only be assigned within its own scope.
- [ ] `groups`, `group_members`, `group_roles` — FRS item 11. Group attributes cover the bases the
      FRS names: geography of cases, size of cases, role in the case.
- [ ] `user_professional_roles` — see the multi-role conflict in 10.5.
- [ ] `user_roles.case_id`, nullable: null means the role is held everywhere in scope, a value means
      it is held on that assignment alone. **Bounded 2026-08-17 to appointed capacities only, and the
      bound is what keeps this table small enough to sit on the authorisation path.**

      A case-scoped role row per *party* would make this table grow as the sum of parties across
      every case rather than with the number of users. One large real-estate CIRP carries tens of
      thousands of homebuyer creditors; a few thousand such cases put `user_roles` into the hundreds
      of millions - on a table the delegation ceiling and every role resolution read on each request.

      It would also be redundant. The programme settled that **a claim creates the party
      relationship**, classified when the resolution professional verifies it, so the claims table is
      already one row per creditor per case. A parallel role row per creditor per case duplicates the
      largest table in the system to record something derivable from it, and gives two sources for
      one fact - which is the shape that eventually disagrees.

      So: `case_id` carries **IRP, RP, Liquidator, AR_CLASS and AR_ENTITY** - appointments, a handful
      per case, made by an order rather than by filing. **Party status is derived from admitted
      claims and is never a role row.** Anything reading "is this user a creditor on this case" asks
      the claims model, not this one.
- [ ] `user_roles.effective_from` / `effective_to` — a capacity on a case is an interval, not a
      current value (10.1). IRP → RP closes one row and opens another, whether the incumbent
      continues or a new IP replaces them on CoC recommendation. Without the interval the platform
      cannot say who was RP on a given date, which is the question an audit asks.
- [ ] `user_roles.scope_id` — which umbrella or organisation node an assignment was made under,
      without which "an IP administers their own users" cannot be evaluated and `ROLES_MANAGE` stays
      platform-wide, as it is today.
- [ ] Capacity uniqueness is **per kind**, not one constraint: `AR_CLASS` unique on
      `(case, class)`, `AR_ENTITY` unique on `(case, creditor_entity)`, and IRP/RP/Liquidator unique
      on `(case)` among rows still open. All of them apply only to rows whose interval has not
      closed, so the constraint is partial rather than a plain unique index.
- [ ] `principal_type` / `principal_id` on the assignment — a class or a creditor entity, null for
      IRP, RP and Liquidator, who represent nobody. A class AR's principal is the **class**, never
      the IP who supplied the panel (10.1).
- [ ] Somewhere to hold an entity AR's **authorisation letter** — evidence of the appointment, on
      the assignment row, with its issuing entity. Depends on document storage, which
      `UploadDropzone` currently stands in for without storing anything (10.1).
- [ ] A **role conflict rule** evaluated per case, not globally: the same individual may not act as
      both IP and Registered Valuer on the same case (IBBI, 13 July §1.3), nor as Liquidator or AR on
      a case where they are the RP (programme, 16 Aug — the s.34(1) continuation is no longer the
      standard, see 10.1). Enforced at assignment, against `(case, person)`. Holding Liquidator on an
      unrelated case is unaffected, so the check must never be written as a property of the person.
- [ ] Ownership and publication columns on every business entity (`owner_user_id`, `owner_org_id`,
      `published_at`), which is what makes FRS item 13's visibility rule expressible at all.

**No `user_permission_overrides` table.** Deliberately absent, and the reason belongs here so it is
not "fixed" later by someone reading it as an omission.

#### 10.3 Rails

- [ ] **Two permission vocabularies exist, and they barely overlap.** Found 2026-08-17. The
      permissions services actually enforce come from the realm and travel in the token — `USER_*`,
      `ORGANISATION_*`, `DOCUMENT_*`, `NOTIFICATIONS_VIEW`, plus the internal `ACCOUNT_PROVISION`,
      `CREDENTIAL_VERIFY` and `PILLAR_LINK_RESOLVE`. The permissions iam's catalogue holds, and the
      RBAC admin screens manage, are `DASHBOARD_VIEW`, `CLAIMS_*` and `REGISTRATIONS_VERIFY`. The two
      sets meet only at `ROLES_MANAGE` and `RBAC_DEFINE`. So most of what an administrator can grant
      is enforced nowhere, and most of what is enforced cannot be granted through the UI. This is
      also why the delegation ceiling reads permissions from iam rather than the token — the claim is
      a projection of the *other* vocabulary. Reconciling them is a prerequisite for the scoped tiers
      above; until then a role's permission list overstates what holding it does.

- [x] **Delegation ceiling — built for assignment 2026-08-17** (`RoleServiceImpl.enforceDelegationCeiling`).
      A grantor may assign only roles whose permissions they already hold. This closed a demonstrated
      escalation, not a theoretical one: `ROLES_MANAGE` alone let any holder assign any role in the
      catalogue, so a `PILLAR_ADMIN` could grant `SUPER_ADMIN` to anyone including themselves, and
      the call returned 204. It is a subset test rather than a denylist of roles nobody may assign,
      because a denylist needs updating every time a role is defined and the forgotten entry is the
      dangerous one.
      **The grantor's permissions are read from this service, not from their token.** The
      `permissions` claim is a Keycloak realm-role projection and it drifts — the realm composite for
      `SUPER_ADMIN` was missing four of the six permissions the catalogue grants it, so a ceiling
      read from the token refused a super admin their own role. iam owns `user_roles`, so it answers
      directly and a stale realm can no longer produce a wrong authorization decision.
      Still open: **the create half** (`RBAC_DEFINE` remains platform-wide), and what happens to
      roles granted by someone who later loses a permission — cascade or flag.
- [x] **`SUPER_ADMIN` is the union of every permission, by construction** (`grantToSuperAdmin`).
      It held all six seeded permissions by coincidence of migration rather than by rule. Creating a
      permission requires `RBAC_DEFINE`, which only a super admin holds, so the first permission a
      super admin defined was one they did not hold — and, once the ceiling existed, one they could
      never assign to anyone either. The ceiling and this invariant are two halves of one idea and
      neither is safe to add alone.
- [ ] **Scope containment.** An IP acts only on their own users; an entity admin only within their
      own subtree. This is an attribute decision about the grant operation itself.
- [ ] **Reason and audit on every change** — role assignment already demands a comment; group
      membership and group-role changes need the same.
- [ ] **No per-user permissions, ever.** Permissions attach to roles; users hold roles. A scoped
      admin who needs a narrower variant defines a role within their own scope rather than editing
      one person's grants — see 10.1. Worth stating as a rail because the API is the natural place
      someone adds it "just for this one user".
- [ ] **Scoped role definition.** `RBAC_DEFINE` is platform-wide today. For a scoped admin it has to
      mean "define a role inside my scope, from permissions I hold", which is the delegation ceiling
      applied to creation rather than assignment.
- [ ] **Assignment closes, it does not delete.** Revoking a role or a case capacity sets an end
      instant; the row stays. Deleting it makes "who was RP in March" unanswerable, which is the same
      defect as a mutable capacity column.

#### 10.4 Attributes and enforcement — the ABAC half

Everything above answers *what may this person do*. These answer *to which records*.

- [ ] Subject attributes: organisation and its **ancestry**, group memberships, professional roles,
      IP scope. Decide whether they travel in the token or are fetched at the decision point —
      ancestry plus groups is not small. **Open.**
- [ ] Resource attributes: owning organisation, owning user, published state, case size, case role.
- [ ] **A representative's decision is derived, not stored.** For any capacity carrying a principal,
      the answer is the intersection of the case scope with what that principal may see — evaluated
      at decision time against the principal's current stake, never snapshotted onto the
      representative's row at appointment. Snapshotting survives the stake it came from: a rejected
      claim or an assigned debt leaves the representative still holding what their principal has
      lost. Granting the representative case-wide access instead discloses one creditor's position to
      another creditor's representative.
- [ ] **Enforcement in two places.** A method gate answers "may this person view cases"; ABAC
      answers "may they view *this* case". Every list endpoint needs the policy as a `WHERE` clause —
      fetch-then-filter breaks pagination and leaks through totals. `UserSpecifications` is the
      existing hook; OPA partial evaluation compiling to SQL is the alternative.
- [ ] **Decision point.** OPA is already in the stack, is in the TAD, and keeps policy reviewable as
      a set — but today it is wired only for service-to-service calls
      (`OpaAuthorizationInterceptor`) and no service consults it. In-process checks are faster and
      unauditable as a whole. **Open**, and it should be recorded either way.
- [ ] **Decision logging** — every denial and every override records policy, inputs and outcome.
      Depends on Stage 6; `LoggingAuditRecorder` is still a stub.

#### 10.5 What the FRS requires, and where the code disagrees

| FRS | Requirement | Status |
|---|---|---|
| Item 6 | A user may select **multiple professional roles**, each with its own identification type and value | **Resolved 2026-08-15.** `V5__multiple_professional_roles` replaces the four columns with `user_professional_roles`, one row per role with its own credential, unique on (user, role). Domain, persistence, API and the registration wizard all carry a list; two set-level rules are enforced (no duplicate role; a legal-representative type only on `LEGAL_REPRESENTATIVE`). Proven end to end: a registration submitting two roles stores both |
| Item 6 | Professional roles are Admin, IP, RV, Legal Rep (Advocate/CA/CS), Authorised Rep | **Conflict.** The seeded catalogue holds twelve, mixing in `FINANCIAL_CREDITOR`, `OPERATIONAL_CREDITOR`, `RESOLUTION_APPLICANT`, `LIQUIDATOR`, `CORPORATE_DEBTOR`, `GOVERNMENT_AUTHORITY`. Those are **stakeholder categories** — a separate axis the FRS lists separately in the stakeholder record. The two are conflated today |
| Item 8 | Entity admin approves AR account creation and assigns permission levels | Matches the entity side of 10.1. Not built |
| Item 9 | Hierarchy Country/Regional/Zonal/State/Branch; upward visibility along direct ancestry | Not built — 10.2 |
| Item 10 | Bulk user creation from Excel; those accounts confirm by mobile OTP at first login | Stage 9. Not built |
| Item 11 | Groups with access permissions **and rights/restrictions**; by geography, case size, case role | Groups: 10.2. **"Restrictions" needs a ruling** — see below |
| Item 13 | Each service carries **Create, Modify, Read, Delete** | **Conflict.** Permissions are ad-hoc names: `ORGANISATION_WRITE` conflates create and modify, `NOTIFICATIONS_VIEW` says view where the FRS says read, and `ROLES_MANAGE`/`RBAC_DEFINE` are not CRUD at all. Formalise permission as (service, action) with the four actions as the default grid and named exceptions for genuinely non-CRUD operations |
| Item 13 | IP data invisible to others until published or granted; on investigation the Admin may publish to the board or adjudicating authority | Not built. Note the override is **specifically publish-to-regulator**, narrower than a general admin override |
| Item 14 | A unique id per user | Stage 8. Not built |
| Item 15 | Federated SSO to IBBI, NCLT, NCLAT, NeSL | Partly built (pillar linking) |

**Open question 8 is answered.** Groups grant roles, so groups do confer permissions. That supersedes
the 2026-08-10 roles-only decision, which predates the FRS reconciliation.

**Two rulings needed before implementation:**

1. **"Rights/restrictions" (item 11).** If a restriction means only "the granted set is what you get",
   the additive model holds. If it means a genuine subtraction at group level, it reintroduces deny —
   and with it the custom protocol mapper, precedence rules and the explainability problem. Confirm
   the reading with the client before building.
2. **The IP-as-authority model is confirmed by the minutes, though not by that name.** The FRS text
   is written around an entity's Admin user, but the IPs asked on 22 July §5 for "super login"
   functionality and "IP Master Access ... to manage permissions and authorise other users", for both
   IP and IPE. The word "facilitator" appears nowhere in either the FRS or the minutes: the attested
   populations are stakeholders, authorised representatives, lawyers, forensic auditors and bank
   users. Use the attested terms.

#### 10.6 What the minutes add beyond the FRS

Read 2026-08-15 from `D:\Desktop\MOM`. These are requirements the FRS does not state, or states
less precisely, and each one changes the model rather than decorating it.

| Source | What it says | Consequence |
|---|---|---|
| IBBI, 13 Jul §3.5 | One account supports every role an IP performs — IRP, RP, Liquidator, AR | Settles the multi-role conflict above |
| IBBI, 13 Jul §1.3 | The same individual may not be IP and RV **on the same case** | A conflict rule that can only be evaluated per case, so it cannot live in a static role model |
| IPs, 22 Jul §5 | IP/IPE super login: invite stakeholders by link, create and manage users **on a case**, assign permissions by stakeholder role, control access to case-specific information | The case scope in 10.1, and an invitation flow that does not exist |
| IPs, 22 Jul §17 | Lawyers may not need a full login; case-specific access may suffice | A limited principal, scoped to one assignment |
| IBBI, 13 Jul | A dedicated login for **forensic auditors** | Another principal type |
| IBBI, 13 Jul §2.9 | IPA users need **controlled access to grievance information** | Cross-organisation read, scoped by regulator relationship rather than by hierarchy |
| IPs, 22 Jul §5 | Banks onboarded, creating and managing their **own** authorised users | A third authority alongside IP and entity |
| IBBI, 13 Jul §3.3 | Public Announcements published through iPIE become **automatically available to IBBI** | Publication changes an audience, which is exactly the unpublished-data rule seen from the other side |
| Weekly review, 24 Jul | On IBBI's instruction, iPIE blocks or restricts an IP's access | **Useful for the restriction ruling:** the restriction cases anyone has actually described are account-level suspension, not the subtraction of a single permission. Account status handles them, and the additive model survives |

That last row is the strongest evidence available for keeping assignment additive. Before adopting
it, confirm the reading of FRS item 11's "rights/restrictions" with the client, but note that no
stakeholder has yet described a case requiring one permission to be stripped from one person.

**A distinction not to lose:** the FRS uses "user group" for two unrelated things. Item 11's groups
carry access rights. Pillar Management G.1–G.4's groups are **communication distribution
lists** (CoC, PRAs, RAs, SCC, IMC) for sending messages and documents. One data model must not serve
both, however similar the word.

### Stage 11 — Authentication and account lifecycle
- [ ] **Forgot-password flow in iam** — a second single-use, hashed, short-TTL token beside `credential_setup_tokens`. It must not be Keycloak's `resetCredentialsFlow` (D1); Stage 2 disables that deliberately
- [ ] Invalidate active sessions on password change (Keycloak admin API: logout the user's sessions)
- [ ] Second factor at login, if open question 7 says yes — the realm already has conditional-OTP sub-flows, nothing requires them
- [ ] Configurable inactivity timeout, and confirm it is enforced on the token, not just in the browser
- [ ] **Account mapping/merge**: search by mobile/email/PAN/CIN, masked PII in results, OTP-authenticated merge, consolidated access afterwards

### Stage 12 — External integrations
Each of these is a synchronous call to a third party on a user-facing path, so each needs the §4.3
treatment — its own timeout, circuit breaker, and a decided answer to "what happens when it is down".
Deciding that per integration is most of the work; none of them may simply fail the registration.

- [ ] MCA/NeSL entity lookup, and address auto-fetch by CIN/PAN
- [ ] Income Tax / MCA validation of CIN/PAN/LLPIN/TAN
- [ ] PAN validation with the nodal agency
- [ ] UIDAI Aadhaar validation — note Stage 5's requirement that the number is never stored in clear text
- [ ] IBBI validation of IP/RV registration numbers
- [ ] Outbound federated SSO *to* IBBI/NCLT/NCLAT, with session-token handling

### Stage 7 — Load testing
- [ ] Build the plan `prepare-registrations.py` was written for: pre-created confirmed registrations, fire only `complete`, find the real ceiling
- [ ] New plans for login and set-password against iam
- [ ] **Acceptance check:** after Stage 3, `complete` must report *no* resilience4j metrics at all — the registries create instances lazily, so silence proves the crossing is gone

### Stage 13 — Source control model and its enforcement
Added 2026-08-15, from the client review of the Development Environment Configuration document.
This stage is **process, not code**, but it constrains every stage above it: once it is in force, no
change reaches an environment except by merging the branch that environment is named after.

- [x] **Branching model agreed and documented.** Git Flow branch *types* promoted along the
      environment ladder: `feature/IPIE-NNN-*` → `develop` (DEV) → `test` (SIT) → `uat` (UAT) →
      `preprod` (PPE) → `main/master` (PROD), with `release/x.y` cut from `develop` for a candidate
      and `hotfix/IPIE-NNN-*` cut from `main/master` and back-merged down the ladder. Promotion is a
      merge of the whole branch, never a cherry-pick — a cherry-pick produces a state that was never
      tested as a whole in the environment below. Recorded in the Development Environment
      Configuration §45 and mirrored into `2TAD-TechnologyStack_N.docx` §5, which previously listed
      the branch types with no environment mapping and no promotion path.
- [x] **Branches created and pushed** — `develop`, `test`, `uat`, `preprod` cut from `master` in all
      six repositories (`ipie-platform-mca`, `ipie-iam-service`, `ipie-user-service`,
      `ipie-communication-service`, `ipie-service-template`, `ipie-web`); 24 new remote branches.
      They carry no commits of their own: each points at `master`'s tip, deliberately, so that the
      five branches start identical and the first real divergence is a promotion rather than
      bookkeeping.
- [x] **Merge to `develop` before starting the next change, and platform before its consumers.**
      Added 2026-08-16 after the model was documented and then not followed for a day: six feature
      branches accumulated 45 commits between them and none reached `develop`, so DEV was running a
      state without the RBAC split, the multi-role migration, or the rate-limit fix that made
      registration impossible in every environment fed from `develop`. Nothing was wrong with any
      commit; the work simply never climbed the first rung. Two rules, both cheap and both learned
      the same day:

      1. **A feature branch is a staging area, not a destination.** Merge it to `develop` as soon as
         it is green rather than pushing the next change onto it. Two symptoms say this has already
         slipped: a branch whose name no longer describes its contents, and a change landing on a
         branch that was cut for something else.
      2. **`ipie-platform-mca` goes first**, in pushes and in promotions alike, because every
         service builds against it. The reverse order publishes a service whose build depends on a
         platform commit nobody can see yet - briefly, but a fresh clone in that window is red with
         the fix nowhere in sight.

      Promotion beyond `develop` is a readiness decision (SIT entry, UAT sign-off) and is never
      automatic; this rail governs only the rung that has no gate on it.
- [ ] **Branch protection applied — BLOCKED, and this is the gap that matters.** The documented
      rules are specified and held runnable at
      `ipie-platform-mca/deploy/github/apply-branch-protection.sh`, but they cannot be applied
      today: the repositories are private under a personal GitHub account, and both the
      branch-protection API and the rulesets API return `403 "Upgrade to GitHub Pro or make this
      repository public to enable this feature."` Verified 2026-08-15 with an admin token, so it is
      a plan limitation and not a permissions one. **Until this is resolved, §45 of the standards
      document describes a control that does not exist** — anyone with write access can push
      directly to `prod`. Resolution is procurement, not engineering: the TAD's own licensing
      section already lists GitHub as a mandatory enterprise licence. Making the repositories public
      is not an option for a government deliverable.
- [ ] **Required status checks** — left `null` in the script until the pipeline publishes named
      checks. A protection rule that requires no check is a review gate, not a quality gate, so this
      is only half-done until CI exists.
- [ ] **`enforce_admins`** — off by default in the script, and this is a deliberate, temporary
      exception. GitHub does not permit an author to approve their own pull request, so with one
      committer and a required approval, enabling it would make every long-lived branch unmergeable.
      Turn it on (`--enforce-admins`) as soon as there is a second reviewer.

Approval counts per branch, as scripted: `develop` 1, `test` 1 (QA Lead), `uat` 2 (QA Lead and
business), `preprod` 2 (Release Manager), `master` 2 (Release Manager and Operations Head) plus a
change record outside GitHub. Force pushes and branch deletion are blocked on all five, stale
approvals are dismissed on new commits, and conversation resolution is required.

### Stage 13a — Migration to the official GitHub account

The repositories currently sit under a personal account. The decision (user, 2026-08-15) is to
migrate to the official account **as a single history-free commit**, not by transferring the
repositories, and the paid plan that branch protection needs arrives with that account.

- [x] **Migration scripted** — `ipie-platform-mca/deploy/github/migrate-to-official-account.sh`.
      Per repository it produces exactly one commit, subject `Initial commit`, holding the working
      tree of a chosen source branch with no ancestry, no prior authorship and no merge record, then
      creates `develop`, `test`, `uat` and `preprod` from it so the five branches again start
      identical. Dry-run verified across all six repositories.
- [x] **Working repositories are never touched.** Each is cloned to a temporary directory and the
      orphan commit is built there, so an interrupted migration cannot damage work in progress.
- [x] **Pre-flight audit refuses rather than warns.** A migration is the last point at which
      unwanted content can be caught, because afterwards there is no history to inspect. The script
      scans the tree and aborts on any assistant reference, and asserts the result is exactly one
      commit before pushing.
- [x] **Estate audited 2026-08-15 and clean** — no assistant references in tracked filenames, file
      contents, commit subjects, commit bodies or author fields, in any of the six repositories. The
      only such directory on the machine sits above the repository roots, outside every working
      tree, so it cannot be swept in. Deliberately **not** added to `.gitignore`: naming it there
      would put the reference into a tracked file in every repository, which is the thing being
      avoided.
- [ ] **Choose the source branch before running.** The default is `master`, which does **not**
      contain the credential-authority work — that is on `IPIE-002-credential-authority`. Either
      merge to `master` first, or migrate with `--source IPIE-002-credential-authority`. Running the
      default today would silently ship an older platform.
- [ ] **Author identity for the official account** — defaults to this machine's git identity;
      override with `IPIE_MIGRATION_AUTHOR_NAME` / `IPIE_MIGRATION_AUTHOR_EMAIL` so the initial
      commit carries the official address rather than a personal one.
- [ ] **Order of operations after migration:** create the empty repositories on the official
      account → run the migration → run `apply-branch-protection.sh` (which will succeed once the
      account is on a paid plan) → re-point every local `origin`, and the platform artifact registry
      URL in each `settings.gradle` and `build.gradle`, at the new owner.

**Consequence to accept knowingly:** a history-free migration discards the commit history recorded
so far, including the reasoning captured in the commit bodies of every change made to date. That
reasoning survives only where it has been written into the repository itself — this plan, the
standards document and the code comments — which is a reason to keep those current rather than to
rely on `git log`.

**For the SDD.** Two things here are platform standards rather than programme mechanics, and belong
in the SDD wherever it describes build and release: (1) an artifact is built **once** and promoted
by immutable digest — the binary validated in UAT is the binary that reaches Production, so no
environment rebuilds from source; and (2) a hotfix is not complete until it has been back-merged
down the ladder, because the alternative is environments that silently diverge from Production. The
branch-to-environment mapping itself is programme configuration and belongs in the Development
Environment Configuration document, not in the SDD.

---

## 6b. Requirements coverage — User Registration & Management

The user stories for this epic, checked against the code on 2026-08-13. "Built" means verified
running end to end this session; "Partial" means the named acceptance criteria are not all met.
The pending work is broken into Stages 8–12 below, one item at a time.

| Story | State | Built | Missing |
|---|---|---|---|
| Account Type Selection | **Not started** | — | Individual/Entity choice; Entity single vs multi-user; fee trigger; immutability without admin override. `AccountCategory` is `INDIAN/NRI/FOREIGNER` — nationality, not account type |
| Entity Affiliation & Search | **Partial** | Search by name over local `organisations`; affiliate-to-entity on the registration draft; unique-id duplicate prevention | Search against **MCA/NeSL**; search by PAN/CIN/LLPIN/TAN as first-class keys |
| New Entity Creation | **Partial** | `Organisation` with `idType`/`idValue` (CIN/PAN/LLPIN/TAN), MSME flag and type, address, legal constitution; DB-level duplicate prevention | Validation of the unique id against **Income Tax/MCA**; address auto-fetch from MCA by CIN/PAN |
| User Registration Form | **Partial** | Name, mobile, email, address, identity proof type + number, professional role and id, organisation | **Mobile OTP**; PAN validation with the nodal agency; Aadhaar validation with **UIDAI**; IP/RV number validation from **IBBI**; NRI/foreign id variants. **Conflict:** the story wants *multiple* professional roles; `V23` deliberately changed the model to a *single* role |
| Mobile & Email OTP | **Partial** | Email OTP issue/confirm, 10-minute TTL, single-use, now stored hashed | **Mobile/SMS OTP entirely** (comms sends email only); configurable expiry; **maximum retry attempts** — currently unlimited guesses against a six-digit code |
| Admin Approves AR Account | **Partial** | Pillar-admin approves a *registration* by emailed link | Entity-admin approving **their own entity's ARs**; pending-request dashboard; approve/reject **with remarks**; per-AR service-level permissions |
| Hierarchical Entity Structure | **Not started** | — | Country/Regional/Zonal/State/Branch levels; parent-child links; upward-cascading visibility; cross-branch isolation |
| Bulk User Creation | **Not started** | — | Excel upload, format validation, row-by-row errors, forced mobile-OTP first login, notification fan-out |
| User Groups & Access Control | **Not started** | — | Groups, multi-group membership, group-level permissions and their precedence over individual ones, groups as filters. Note the 2026-08-10 decision that permissions reach a user *only* through a role — groups need that decision revisited |
| Post-Login Dashboard | **Partial** | Login (password grant → SPI → iam), role-specific dashboards in ipie-web | **OTP as a second factor at login** — the realm has conditional-OTP sub-flows but nothing requires them; configurable inactivity timeout |
| Service-Level CRUD Permissions | **Partial** | Roles, permissions, assignment, `RBAC_DEFINE`/`ROLES_MANAGE` split | Per-service C/M/R/D granularity; the **unpublished-data rule** (IP-entered data invisible until published or granted); admin override for investigations |
| Unique User ID Generation | **Partial** | UUID primary key, immutable | A **defined human-readable convention** — the story wants a format, and a UUID is not one |
| Federated SSO | **Partial** | Inbound: pillar link initiate/callback, `pillar_links`, first-broker-login SPI authenticator, IBBI/NCLT/NCLAT/NeSL pillar types | Outbound navigation *to* the pillar portals; session-token handling across them |
| Account Mapping from Existing Systems | **Not started** | `pillar_links` is the adjacent primitive | Search by mobile/email/PAN/CIN; **masked PII** display; OTP-authenticated merge; consolidated case access |
| Profile Update | **Partial** | Update name/contact/address; audit trail as the change log | **Re-verification on change** — a new mobile or email must be re-proved by OTP, and PAN re-validated, before it takes effect (§4.1.1 already says an email change must be re-verified or the login identifier becomes changeable without proof) |
| Password Management | **Partial** | Change password while logged in (current password required, iam-owned) | **Forgot-password flow entirely.** Note the conflict: Stage 2 disables Keycloak's `resetCredentialsFlow`, which is correct under D1 — so this must be built in **iam**, as a second single-use token beside `credential_setup_tokens`. Also missing: **invalidate active sessions on password change** |
| Case Access on Dashboard | **Out of scope here** | — | Belongs to a case service that does not exist yet; listed so it is not mistaken for a registration gap |

**Three things need a decision before they can be built** — they are in §7 as open questions 6, 7 and 9:
single vs multiple professional roles, whether login gains a mandatory second factor, and whether
group-based permissions are allowed to bypass the roles-only rule.

## 7. Open questions

1. **Argon2id parameters** — memory, iterations, parallelism. Needs a decision benchmarked on the target hardware.
2. **Lockout owner** — Keycloak's brute-force detector, or iam's own counter? Keycloak's is already configured (10 failures, 15-minute backoff, not permanent). Keeping it is less code; moving it to iam puts all credential policy in one place.
3. **`ipie-audit-service`** — new repository, or a module in an existing one?
4. **Resource-owner password grant** — it is deprecated in OAuth 2.1 and ipie-web already has `pkce.ts`. Move login to authorization-code + PKCE, or keep the password grant?
5. **Existing accounts** — any Keycloak users with passwords today need a migration path (rehash-on-next-login, or forced reset).
6. **One professional role, or several?** `V23__change_professional_roles_to_single_role` deliberately collapsed this to one, and the "User Registration Form" story asks for several. One of the two is wrong; reverting a shipped migration is the more expensive direction, so decide before Stage 8.
7. **Second factor at login.** The "Post-Login Dashboard" story specifies email + password + OTP. Today login is the password grant with no second factor. This interacts with open question 4 — moving to authorization-code + PKCE would make a second factor natural, whereas bolting OTP onto the password grant would not.
8. **Consent notice versioning.** DPDP ss.6–7 require consent to be itemised and withdrawable, which means a consent row has to record *which notice text* the person agreed to — consent to a notice nobody can produce afterwards is not evidence. Two options: point at an existing notice document with real version numbers, or model a `consent_notices` table the platform owns and seed a v1. **Blocks Stage 5 item 5.**
9. **Do groups get to grant permissions directly?** The 2026-08-10 decision was that permissions reach a user *only* through a role — no per-user overrides, explicitly considered and declined. The "User Groups" story wants group-level permissions that "override or supplement individual permissions", which is that same question in a new shape. Either groups are collections that carry roles, or the roles-only rule goes.

---

## 8. Change log

| Date | Change |
|---|---|
| 2026-09-02 | **Platform 0.1.0 still is not published anywhere shared, and the way to publish it is now written down as a script.** Every build file points at `maven.pkg.github.com/ipie-cms/ipie-platform-mca` - root build and `ipie-build-conventions` both - but nothing has ever resolved from it: there is no `~/.gradle/gradle.properties`, no `in.gov.ipie` in the Gradle cache, and `settings.gradle` lists `mavenLocal()` first, so local builds have been satisfied from `~/.m2` the whole time. A fresh clone by anyone else would fail at settings evaluation. **Blocked on a credential:** Git Credential Manager issues a `gho_` OAuth token scoped `gist, repo, workflow` - no `write:packages` - and the Maven registry needs a CLASSIC PAT (fine-grained tokens do not work against it); `push` on the target repo suffices, admin does not. Note a 401 from that registry does not distinguish "package absent" from "token cannot look", so it proves nothing about what is published. **Method, deliberately not the obvious one:** `deploy/github/publish-platform-0.1.0.sh` uploads the released bytes from Maven Local rather than rebuilding them. Setting `version=0.1.0` and running `./gradlew publish` would rebuild from a tree that has moved on since 2026-08-31 and hand a different 0.1.0 to whoever pulls next - the mutable-version failure that cutting 0.1.0 was meant to end. Cut a new version instead if the platform needs to change. The 14 files include `ipie-build-conventions` and its four plugin markers, which the root `publish` task silently omits because it is an `includeBuild`. |
| 2026-09-02 | **No `.docx` is version controlled anywhere in the estate any more; the `.md` beside each is the only tracked format.** The `docs/` change earlier today left five root-level exceptions un-ignored by name - `MASTER_CODE_STANDARDS.docx` in all five repositories and `Architecture_Plan.docx` in this one. Those negations are gone, the files stay on disk, and `.gitignore`'s blanket `*.docx` now has nothing carved out of it. **Why:** the exceptions were the last compressed archives in the published estate, and a compressed archive is exactly what defeats a `grep`-based audit - keeping two of them while ignoring nineteen others bought a readable copy in the browser at the cost of the one property that made the estate auditable. The generation discipline is unchanged and now unconditional: `pandoc X.md -o X.docx --toc`, source in git, deliverable by hand. `MASTER_CODE_STANDARDS.md` §Documentation format discipline was rewritten to say so and re-mirrored across the four service copies (md5 identical); `Architecture_Plan.md` no longer points at a per-service `.docx` that git carries. All five `ipie-cms` repositories were re-migrated again. |
| 2026-09-02 | **`docs/` is now local-only in every repository; the three service repos stopped tracking theirs.** `ipie-user-service`, `ipie-iam-service` and `ipie-communication-service` each carried their LLD, ERD, `Architecture_Plan.md` and draw.io sources in git, un-ignored by an explicit `!docs/*.docx` / `!docs/*.md` pair; `ipie-platform-mca` and `ipie-service-template` had always ignored `/docs/` outright. The split is gone - all five now ignore it, and the files stay on disk. **Why:** a `.docx` is a zip, so its text is DEFLATE-compressed and `grep`/`git grep` cannot see inside one. `ipie-iam-service/docs/ERD_User_IAM_Services.docx` had carried an editing-tool style name in `word/styles.xml` through every audit that passed, and was only found by unpacking the archive part by part. Ignoring the folder removes the class of problem instead of re-auditing each file forever - a generated deliverable has no reason to be in version control when its source discipline is `.md` in, `.docx` out. **What this costs:** the per-service architecture plans, ERDs and diagram sources no longer travel with the code, so they reach a reviewer by hand. Anything that must arrive through git belongs at the repository root, un-ignored by name, beside `MASTER_CODE_STANDARDS.md` and `SERVICE_CLASS_REFERENCE.md`. The documents are still updated alongside the code that changes them - that rule is unchanged. The five `ipie-cms` repositories were re-migrated so their single `Initial commit` never contained a `docs/` folder at all. |
| 2026-08-24 | **Log aggregation built (ELK): services -> Logstash -> `elasticsearch-logs` -> Kibana.** Structured logging had been half-built since inception - every service emitted a well-formed JSON line via `logstash-logback-encoder` and *nothing collected it*, so reading a log meant `docker compose logs` per container, lost on recreate, and following one request across four services meant grepping four outputs. The library's name had repeatedly been mistaken for evidence that an ELK deployment existed; it is an in-process formatter and opens no socket. **The conventional shipper design is impossible here and that is the finding worth carrying:** on Docker Desktop's WSL2 backend the daemon's container-log directory sits inside the `docker-desktop` VM and is unreachable from a container by any bind mount - both `/var/lib/docker/containers` and `/run/desktop/mnt/host/var/lib/docker/containers` mount empty, and the former is silently *created* empty on the Ubuntu side, which reads as a partial success. That rules out Filebeat, Fluent Bit and the OTel collector's `filelog` receiver alike; a Filebeat version was built first and discarded on this evidence. Transport is therefore `LogstashTcpSocketAppender`, which already shipped in the JAR `common-observability` depended on - **no new dependency, configuration only** - wired entirely inside `common-observability`'s shared fragment under a new `elk` Spring profile (**the platform's first profile**; anything later needing one must append to `SPRING_PROFILES_ACTIVE`, not replace it). Two silent-failure traps recorded in Section 9 of the standards: `<springProfile>` nested inside `<root>` is ignored with only a Logback status WARN, and copying `service` to `service.name` in the Logstash filter makes Elasticsearch reject *every* document on a mapping conflict. **Verified end to end** - a request with a known `X-Correlation-Id` produced five lines, all five found in Elasticsearch with `correlationId`, `traceId`, `spanId`, `service` and `environment` intact. `elasticsearch-logs` (9201) is deliberately a second cluster, kept off the `elasticsearch` (9200) that backs the user-search read path. **It also exposed a gap:** the four services contain *zero* `log.*` statements of their own - everything shipped today is framework logging, and a successful request logs nothing - so deciding what the services should log is now the work that makes this pipeline worth querying. Licensing recorded in Section 12 of the standards: Elastic Basic is free and needs no key, but Kibana SSO via Keycloak and Elasticsearch audit logging are **Platinum**, and that procurement decision is open. |
| 2026-08-18 | **Flyway pinned ahead of the Spring Boot BOM, because the databases moved to PostgreSQL 18 and V13 pinned them there.** Every migration ran under a warning that the combination is untested: Boot 3.5 manages Flyway 11.7.2, which supports PostgreSQL up to 17, and the platform's databases run 18.4. It migrated correctly, and the risk was never "corruption tomorrow" - it is that on an unknown-newer server Flyway falls back to its newest known handler, so anything it leans on that changed (the advisory lock that serialises replicas starting together, the catalog queries behind the history table, transactional-DDL edges) surfaces at deploy time, in the one place that is hard to roll back. A routine dependency bump can also turn that warning into a refusal to start. **Downgrading the database was not available:** `V13` uses `uuidv7()`, a PostgreSQL 18 built-in, so that migration raised the platform's floor to 18 - a consequence worth naming, since it converts this bump from hygiene into a requirement. Pinned to `11.20.3` in `ipie-parent`'s `approvedVersions` with constraints on `flyway-core` and `flyway-database-postgresql`, which outrank the imported Boot BOM (`11.7.2 -> 11.20.3 (c)` in all four repositories). **Deliberately inside major 11** rather than the latest 13.3.0: Spring Boot 3.5's Flyway auto-configuration is written against the 11 line, and a major jump would risk startup to fix a warning. **Licensing checked rather than assumed** - 11.20.3 ships a `META-INF/LICENSE.txt` byte-identical to 11.7.2's, Apache 2.0; the Redgate paid-edition gating classes are present in both and none of the gated features are used. Verified on the running stack against the real 18.4 database: no unsupported-version warning in any of the three services, and 14/22/3 migrations still validate, so no checksum moved. The comment above the BOM re-export, which claimed Boot pins Flyway, was corrected - a comment that lies about version governance is how the next bump gets diagnosed as not working. |
| 2026-08-18 | **Bearer-secret hashing consolidated, and the rule that picks between the two modes written down.** ipie-user-service peppered its OTP and verification-token digests with HMAC-SHA256; ipie-iam-service fingerprinted its credential-setup tokens with a bare SHA-256. Both were correct and the reasoning existed nowhere, so a third service would have guessed - and a wrong guess here is invisible until someone reads the database. `common-security`'s new `secret` package holds both modes with the decision recorded on the port: **it is the entropy of the input that decides, not how sensitive the secret feels.** A six-digit OTP has a search space of one million, so an unkeyed digest of it is reversible by anyone holding the database with no cryptographic weakness involved; a 256-bit `SecureRandom` token has no dictionary to run and a pepper would only add a key to lose. `PepperedSecretHasher` refuses to construct without a pepper rather than falling back to an unkeyed digest, which would look identical in every log and test while protecting nothing. Neither is a password hasher - that is Argon2id and it stays in iam, which owns credentials. **The move had to be byte-identical or it would have failed silently:** every unexpired OTP, verification token and setup token is already stored as one of these digests, and a changed encoding would not error, it would stop matching everything issued before the deploy. The known-answer tests assert vectors computed independently of the Java code, so they check the implementation rather than agreeing with it. Both services keep their named collaborators (`RegistrationSecretHasher`, `CredentialSetupTokens`) as thin wrappers, because "which hasher" is a security decision that should be visible at the injection point rather than a bare port anything could satisfy. |
| 2026-08-18 | **The platform's own adapters brought back into the platform, and a rule so they stay there.** An audit of what the services reinvent found the substantive answer reassuring - no service rebuilds error handling, paging, filters, HTTP clients, JSON, rate limiting, correlation or masking - and one large exception: the storage half of the eventing stack existed as four byte-identical copies. `JpaOutboxStore`, `JpaProcessedEventStore`, their Spring Data repositories, the two row shapes, `OutboxRelayScheduler`, `JpaAuditingConfig` and `OpenApiConfig` were the same in ipie-service-template and all three services, about 440 lines held in four places. **Nothing had drifted yet** - the copies differed only by a trailing newline, so this was a cost still to be paid rather than one already paid, though it had been paid once before: adding `deletePublishedBefore` to `OutboxStore` required editing every copy and left a common-libs test fake uncompilable. **Why it happened is worth recording, because it was not carelessness.** Standards 13.3 already said not to build cross-cutting things locally, and it looked satisfied: the *port* was in common-libs. The gap was that 13.3 governs concerns and never answered where the single implementation of a port belongs. The rule now has that clause, and a fourth mechanically-enforced ArchUnit rule (`noHandWrittenPlatformPortAdapters`) fails any service that implements either port without an explicit `@PlatformOverride("reason")` - prose had already failed once here. **What moved and what deliberately did not.** The stores, entities, relay, JPA auditing and OpenAPI beans moved; the entity row shapes became `@MappedSuperclass` so a service declares only its `@Table`, which is the one thing that genuinely differs - ipie-iam-service prefixes its platform tables because it shares ipie-user-service's database (EX-001). Queue and topic names, consumer configs, permission catalogues and domain events stayed. So did iam's Argon2id `PasswordHasher`, since being the credential authority is that service's job. **Two findings recorded rather than fixed:** the four `examples.*` demo classes ship in `src/main` of user-service and iam-service though their own Javadoc says they are wired into nothing; and bearer-secret hashing diverges silently - user-service peppers with HMAC-SHA256 (right for a six-digit OTP with a 10^6 search space), iam uses a bare SHA-256 (defensible for a 256-bit random token), with that reasoning written down nowhere, so a fourth service will guess. **Found while moving OpenApiConfig:** every service was serving the template's description, "User CRUD reference implementation built from the approved iPIE service template", as its public API summary - the title is now `spring.application.name` and the description a property. Net effect across the five repositories: roughly 875 lines of copied infrastructure deleted, about 200 lines added to the platform, and the platform's own JPA code now has database-backed tests of its own for the first time. |
| 2026-08-18 | **`users` became a principal table and the person detail moved out — the first cut of 10.2, built as user-service `V13`.** A person is a principal and so is an entity: `person` holds name, contact, address and identity proof with `user_id UNIQUE NOT NULL`, `organisations` gained a `user_id` of its own, and `users.is_org` says which detail table holds the row. Both references point from the detail to the principal, which is the direction that admits a foreign key at all — a `ref_id` on `users` aimed at one of two tables could carry none. Every one of the 39 seeded principals kept its detail through the move, each organisation gained a principal named after the government id it already carried (`pan-aabcu9603r`), and `organisation_professional_roles` (V11) folded back into `user_professional_roles`, whose value column was **widened to 100 first** rather than truncated into — V11 declared 100 against V5's 50, and `left(value, 50)` would have silently dropped the tail of a recognition number. **The cross-table invariant is enforced where the write happens and checked where the data is made:** `OrganisationJpaEntity` builds its principal in its constructor, so no order of calls leaves an organisation without one, and V13 ends by counting principals whose `is_org` disagrees with their detail and raising rather than completing — a principal with `is_org` false and no `person` row is blank on every screen that reads a name, which reads as missing data rather than as a broken model. **An entity principal is deliberately not a mailbox**: its email uses the reserved `.invalid` domain, so nothing can quietly start mailing an entity in place of the authorised representative, and it is `VERIFIED` rather than `PRE_REGISTRATION` because no registration was ever started for it — leaving it mid-workflow would have put every entity into the pending-registration queues. **A user search is a search for people**: entity principals now share the table, so `UserSpecifications` excludes them; without that an admin listing returns rows named `cin-…` that read as corrupt data. The identity proof moved as it stands — the catalogue holds exactly PAN and AADHAAR, so the type plus hash plus masked last-4 already expresses "PAN, or Aadhaar where there is no PAN"; that rule is **not** a check constraint because a PRE_REGISTRATION draft legitimately has neither yet, and collecting Aadhaar carries DPDP obligations a column cannot express either. In Java the split is invisible above the repository: `UserJpaEntity` keeps its accessors and reads through the person row, so the mapper, the services, the DTOs and the search index are untouched. **Found while doing it:** V11 and V12 never granted their new tables, so `organisation_closure` — read by `UserSpecifications` on every hierarchy-scoped search — is unreadable by the app role in any environment where the least-privilege roles exist, and works locally only because migrations run as a superuser there. Granted in V13 alongside `person`. **Not in this change, deliberately:** `org_partners`, `user_hierarchy`, `ip_afa`, and the retirement of `users.organisation_id`/`organisations.parent_id` — the last would invalidate V12's closure table and collides with the still-open FRS item 9 requirement for `parent_id` plus COUNTRY/REGIONAL/ZONAL levels. |
| 2026-08-18 | **Integrity violations translated into the error they actually are, in common-libs.** Asked whether a duplicate id inside one millisecond can be handled when the insert fails, and the answer turned out to be that the platform could not tell an id collision from a duplicate email in the first place: every repository caught `DataIntegrityViolationException` unconditionally and answered with one fixed message - ipie-user-service's said "a user with the same username or email already exists" for a duplicate **phone number**, a bad foreign key, a failed check constraint and a primary-key collision alike. `IntegrityViolations` (common-libs `persistence`) now reads the constraint name from Hibernate's own `ConstraintViolationException` and each repository declares the constraints of the table it owns; an undeclared constraint is rethrown rather than guessed at, because a foreign key is a bug to see and not a 409 telling the caller to change something they never sent. **A collision is deliberately not a `ConflictException`**: a conflict says the request is wrong and will fail again, a collision says the platform generated a duplicate and the same request with a fresh id would succeed, and reporting the two alike is what sends whoever is debugging it to the wrong place. **Retrying was considered and rejected as a blanket rule** - a duplicate-email insert would be retried too, turning one correct 409 into several pointless attempts and then a 500 - and rejected for the id case as well, because by the time the exception surfaces Hibernate has already assigned the generated id to the entity, so saving it again collides identically; only the caller can mint a new one. With version-7 ids the case needs two identical 74-bit draws in the same millisecond, so its real value is diagnostic: seeded rows with hand-written literal ids are the plausible cause, and an `IdCollisionException` in a log means id generation, not load. **Three defects found while wiring it, each invisible from the code that looked correct:** the declarations were private constants, so the boundary handler's provider resolved to an empty list and the deferred-flush path it exists for was dead - a violation raised at commit, after the repository returned, reached no declaration at all; the boundary asked every table's declaration to translate, so a table configured with a fallback message would have claimed another table's foreign key; and an `IdCollisionException` thrown from a repository's own catch fell to the `IpieException` catch-all and came out as **422** while the same collision at the boundary came out as 409. All three fixed - declarations published as beans (`IntegrityViolationsConfig`, including `user_professional_roles`, which has no repository of its own), `declares(constraint)` consulted before translating, and one handler both routes go through, with the violation carried as the collision's cause so a single log line holds the failed statement. Constraint names verified against the migrations, `V8`'s stakeholder→pillar renames included. **Also fixed by running the tests:** the shared Elasticsearch testcontainer fixture set no heap bound, so Elasticsearch sized its heap from the whole host and was OOMKilled before it logged `started` - which surfaced as a wait-strategy timeout and reads like a slow start rather than a memory ceiling. |
| 2026-08-16 | **The branching model put into practice, and the rail that was missing added to Stage 13.** The ladder had been documented for a day and followed for none of it: six feature branches held 45 commits between them and `develop` had none, so DEV was running without the RBAC define/assign split, `user_professional_roles`, and the registration rate-limit fix - the last of which meant nobody could complete a registration in any environment fed from `develop`, since the wizard's four lookup calls were eating a five-per-minute budget the write flow needed four of. All six repositories promoted to `develop` as whole-branch `--no-ff` merges, platform first: ipie-platform-mca `ae2af4a`, ipie-iam-service `94fe1ce`, ipie-user-service `9f698f2`, ipie-communication-service `667b155`, ipie-service-template `17e25d0`, ipie-web `95c144b`. `develop` diverges from `test` for the first time, 6 to 12 commits per repository. Stage 13 gains the rail that would have prevented the drift - merge to `develop` before starting the next change, platform before its consumers - with the two symptoms that say it has already slipped: a branch whose name no longer describes its contents, and a change landing on a branch cut for something else. **Also this day:** `./gradlew check` made to pass in ipie-user-service for the first time in the repository's history (verified failing at its initial commit) - five records copying their professional-roles list defensively, RegistrationSecretHasher sealed against a finalizer attack on its throwing constructor, the OutboxStore collaborator excluded, and UserServiceImpl's twelve-argument constructor cut to seven by extracting UserEventPublisher and RegistrationPolicy; and a test fake in ipie-common-libs that had not compiled since `deletePublishedBefore` was added to OutboxStore. The working plan itself moved into ipie-platform-mca as ARCHITECTURE_WORKING_PLAN.md - it could not keep its old name on a case-insensitive filesystem - and the 48 references to it across the repositories were repointed, which broke five applied migrations' checksums until all three Flyway history tables were repaired. |
| 2026-08-15 | **The capacity lifecycle and delegated administration recorded in Stage 10.** The programme set out how an IP actually holds roles: NCLT admits a petition, the company enters CIRP, an IP takes it as IRP, and the CoC then either confirms them as RP or recommends a different IP who replaces them; Liquidator and AR are held the same way, at case level. Three consequences are now written into 10.1 and 10.2. **A capacity is an interval, not a value** — `effective_from`/`effective_to` on the assignment, because IRP → RP must leave the IRP period intact for anything signed during it; a mutable capacity column answers only "what are they now". **Replacement is the same operation as transition**, so authorization is evaluated at a point in time and not merely per case. **Exclusivity is per case** — the RP on a case may not act as its Liquidator or its AR, extending IBBI's 13 July rule about IP and Registered Valuer. The exclusivity rule was confirmed on 16 August: the RP on a case can never be its Liquidator and cannot be its AR, while holding Liquidator on an unrelated case is fine — so the check is against `(case, person)`, never against the person, and the s.34(1) continuation is recorded as no longer the standard so that nobody reinstates it from the bare section. **"AR" turned out to be two things**, settled the same day: a class AR represents a class of creditors and *is* an IP, selected by the class, unique per `(case, class)`; an entity AR is sent by a creditor entity, is ordinarily not an IP, and holds an authorisation letter from that entity, unique per `(case, creditor_entity)`. They are modelled as two capacities rather than one with a nullable class, because their admissibility rules differ — one asserts an IBBI registration, the other a verified letter. The letter is evidence of an appointment and belongs on the assignment, which is a second reason `AUTHORIZED_REPRESENTATIVE` has no place in the professional-role catalogue. It also has nowhere to live today: the registration form offers an Authorization Letter upload, but `UploadDropzone` is UI-only by design and stores nothing, so the field collects a filename and a reviewer would assume a letter had been captured. Still open: whether an entity AR's authorisation is per case or standing across that entity's cases. **Generalised the same day:** a representative is always bound to a principal, so a capacity row carries an optional `principal_type`/`principal_id` — a class or a creditor entity, null for IRP, RP and Liquidator, who represent nobody. The principal of a class AR is the **class**, not the IP who supplied the panel: recording the IP would make RP replacement look as though it should cascade to the AR, would imply a duty running to the very person the AR exists to check in CoC voting, and would derive the AR's visibility from the RP's. Which settles how the ABAC answer is computed — a representative's access is the intersection of the case scope with what their principal may see, evaluated live rather than snapshotted, since a rejected claim or an assigned debt must take the representative's access with it. And a boundary: the IP-umbrella relationship is administrative, not representative — a delegate acts for the IP, an AR acts against the RP's position when the class requires it, and one table for both would let a representative inherit the access of the person they scrutinise. **Delegated administration** is recorded as one scoped role rather than a role per level — super admin, entity admin and an IP administering their own umbrella are the same definition at `PLATFORM`, `ORG` and `IP` scope — with the IP practice modelled as an organisation node so one hierarchy serves both. **Users hold roles and never permissions**: the request for an admin who could grant permissions per user is refused in the plan, because it makes "who can approve a claim?" unanswerable by review; the need behind it is served by letting a scoped admin define a role inside their own scope from permissions they already hold. The two subset rules in 10.3 are marked load-bearing rather than hardening — without the delegation ceiling, an IP admin's first act can be to grant themselves SUPER_ADMIN. |
| 2026-08-15 | **Multiple professional roles restored; the FRS and the minutes reconciled into Stage 10.** Read `IPie FRS.pdf` (User Management items 8–15) and the meeting minutes in `D:\Desktop\MOM`, and rewrote Stage 10 around what they actually require. The FRS allows several professional roles per person, each with its own identification type and value; IBBI settled any doubt on 13 July. `V5__multiple_professional_roles` replaces the four columns on `users` with a child table, one row per role, unique on (user, role) — the credential belongs to the role, since an IBBI number proves an IP and says nothing about the same individual acting as a legal representative. Domain, persistence, API, registration wizard and the end-to-end run all carry a list; a registration submitting two roles now stores both. **Two defects found by running rather than by testing:** roles were applied only on the insert path while a real registration updates the pre-registered row, so none were stored; and clearing the collection before re-adding made Hibernate order inserts before orphan deletes inside one flush, violating the unique constraint and dead-lettering the provisioning event — reconciling by role fixes it and preserves each row's audit stamps. **Also recorded:** the distinction between a professional role (a qualification, set at registration) and a capacity on a case (IRP, RP, Liquidator, AR — per assignment, "a single login but the role changes by the case id"), which are both called "role" and are not the same thing; three other FRS conflicts (the professional-role catalogue conflated with stakeholder category, permissions not expressed as Create/Modify/Read/Delete, and two unrelated meanings of "user group"); and what the minutes add beyond the FRS — case-scoped access, IP/IPE master access, forensic auditors, IPA grievance access, banks managing their own users, and IBBI-instructed account suspension, which is the evidence that restrictions are account-level rather than permission-level. |
| 2026-08-15 | **Rebuilt from nothing and verified end to end: 27 checks, all passing.** Snapshots purged from Maven Local and the platform republished from source (the conventions plugin separately — the root build does not publish an `includeBuild`); all four images rebuilt, ipie-web among them for the first time; both databases dropped and recreated, taking the old Flyway history and the ~307 load-test rows with them. `ipie-platform-mca/e2e-test.sh` now walks the whole chain — login through Keycloak, the SPI, HMAC and iam's Argon2id hashes; registration, OTP by mail, completion, asynchronous provisioning, the set-password link, the new account logging in — and asserts what cannot be seen from outside: the OTP and identity number never stored in clear, consent recorded per channel, the user-service role refused on the credential tables, the rate limit returning 429, nothing dead-lettered. **Two defects found by rebuilding, both invisible until a database was created from scratch:** the generated baselines carried `COMMENT ON EXTENSION pgcrypto`, which requires owning the extension and which the scratch verification missed because it ran as superuser rather than as the migration role; and ipie-user-service had no `baseline-on-migrate`, so under D6's shared schema whichever service started second refused outright — iam had carried the setting since it was added to the shared database, user-service never did. **Also documented here and in the standards:** Keycloak must be started with `spi.dev.env` loaded, or the token endpoint answers `unknown_error` while iam sees no request at all. A test-case document (`iPIE_Test_Cases_Ver1.0.docx`, 46 cases across ten areas) records what is automated, what is manual and what each last returned. |
| 2026-08-15 | **Client review of the Development Environment Configuration answered; Ver 1.2 issued.** Ten observations from G&PS – DGA, all addressed in `Development_Environment_Configuration_Ver1.2.docx` (Ver 1.1 left intact): new §40 workstation specification, §41 version matrix (43 rows, each naming its authoritative source), §42 twelve-step onboarding checklist, §43 environment endpoints/connectivity/ownership, §44 access management matrix, §45 branching model, §46 disaster recovery, and Appendices D (environment variable catalogue, 46 rows classified secret vs config), E (exception register) and F (response to each review comment). §11 and §33 now distinguish local development *configuration* from *secrets*, with the test that decides which a value is; §29 extended to the full ladder including PPE, PROD and DR. **Two items are recorded as formal exceptions rather than smoothed over:** EX-001, ipie-user-service and ipie-iam-service sharing one database (D6) with the database-level grants that make the boundary survivable; and EX-002, the synchronous login hop to iam (§4.3). Ten `.drawio` sources with matching `.png` renderings added to the relevant sections, generated from one shared spec so source and image cannot drift, plus a `List of Figures` field mirroring the existing `List of Tables`. Appendix A grew from 69 to 117 abbreviations after auditing every acronym used in the document rather than only the ones this revision introduced. **Values deliberately not invented:** environment URLs and named owners are `<TBC>`, and the RPO/RTO figures (PROD 15 min / 4 h) are marked proposed pending sign-off against contracted service levels. Three governance risks are stated rather than hidden: Bouncy Castle pinned outside the BOM, `latest` tags on three container images, and the mutable `0.1.0-SNAPSHOT` platform version that pins a number without pinning its contents. |
| 2026-08-15 | **Source control model put into effect — see Stage 13.** 24 branches created and pushed; branch protection specified, scripted and **blocked on the GitHub plan**, which means the standards document currently describes a control that is not in force. |
| 2026-08-15 | **Two defects fixed, and the branch pushed.** (1) iam configured `ipie.security.rate-limit` rules but never registered `RateLimitFilter`, so the only unauthenticated route into the credential store — `POST /api/v1/credentials/password` — was bounded by nothing but its token's entropy. Proven live *before* fixing, against the running pre-fix container: 7 of 7 calls returned 422, no 429. After: 5 through, 6th and 7th 429 in the standard `ApiError` shape. Filter order matches ipie-user-service exactly; an unsigned call to an HMAC-protected path still returns `401 INVALID_SIGNATURE`, so the chain change did not disturb HMAC. (2) user-service's `application-prod.yml` had **two top-level `ipie:` keys** — the pepper block was appended without noticing one existed — so `ipie.security.enabled: true` and the S3 SSE settings were silently discarded, and under Boot's stricter loader the prod profile would not start at all. Merged; verified under a duplicate-rejecting loader. Also found while verifying: `ipie.java-conventions.gradle` extracted the packaged Checkstyle/SpotBugs config only when the destination did not already exist, so a service kept its first extraction forever and a rules change silently never arrived — extraction is now unconditional. **Pushed**: ipie-platform-mca (7 commits) then ipie-iam-service (5), branch `IPIE-002-credential-authority` on both, platform first because iam implements the platform's new `OutboxStore.deletePublishedBefore`. **Held back deliberately**: the RBAC define-permission feature (iam `V16` + `RoleController`/`RoleService`/`Permission` + the `RBAC_DEFINE` realm role in `realm-export.json`) and the `docs/` LLD updates that describe it. **Open**: whether `RateLimitFilter` should be auto-registered by the platform in `configureBaseline` rather than per service — a reviewed recommendation says yes, but only after `IPIE_TRUSTED_PROXIES` is set and a chain-composition test exists, since auto-registration turns a fail-open gap into a possible fail-closed one platform-wide. ipie-user-service's `SecurityConfig` also carries a comment stating the reverse of what its code does (actual order: HMAC → rate limit → UPAF); left alone, since it is behaviourally inert today. |
| 2026-08-13 | **Stage 5 started** — items 1 and 2 done (see their checkboxes). Items 3, 4 and 5 remain. |
| 2026-08-13 | **Requirements coverage reviewed** against the User Registration & Management stories (§6b), and the pending work broken into Stages 8–12. Two direct conflicts with what is already built came out of it, both now open questions: the stories want multiple professional roles where `V23` deliberately collapsed to one, and they want a forgot-password flow where Stage 2 deliberately disables Keycloak's — the latter is not a contradiction so much as a statement that it must be built in iam. A third, groups granting permissions, reopens the 2026-08-10 roles-only decision. |
| 2026-08-13 | **Re-verified end to end on the real stack, and one defect found by doing it.** 13/13 checks: login (correct and wrong password), the full registration chain — register with no password → OTP → `complete` returning `PROVISIONING` with no `keycloakUserId` → async provisioning → set-password mail to the **registrant** → password set against iam → new user logs in → replay 422 — the row settling at `UNVERIFIED` with a Keycloak id, the approval mail going to `admin@ipie.gov.in`, zero dead letters, and Stage 7's acceptance check (**no** resilience4j instances on the `complete` path, proving the synchronous crossing is gone). **The defect:** all three SPI clients defaulted `IPIE_KEYCLOAK_BASE_URL` to `http://localhost:9090` — Prometheus in this stack — so with the variable unset the client-credentials fetch 404s and *every* login fails closed with `503 temporarily_unavailable`, indistinguishable from the port-forwarding and HMAC faults. Fixed to `:8080`, and the wider fix per §0.1: `SpiConfig` now holds every setting, applies defaults in `dev` only, and validates at startup from each factory's `init`, so a deployed environment cannot inherit a laptop's values. Added `deploy/keycloak/env/spi.{dev,test,uat,pre-prod,prod}.env` (secrets only in dev; placeholders elsewhere, unverified against any real environment). `start-stack.sh` now resolves Keycloak's address instead of assuming `localhost` (which a WSL shell cannot reach when Keycloak is on the Windows host), fails if the deployed SPI jar still carries the 9090 default, and names the 9090 cause in its 503 hint. Also fixed: prometheus was down from a stale Docker bind-mount after a Docker Desktop restart — `docker start` cannot repair that, `up -d --force-recreate` can. |
| 2026-08-11 | Created. Design agreed after the superseded async-provisioning change was reviewed and its dead-end defect found (§1.3). |
| 2026-08-11 | Added §4.1.1, the iam / user-service boundary, after the question "iam should be Identity Access Management — how should the roles be segregated?" |
| 2026-08-12 | **END TO END PROVEN.** Services rebuilt as containers (the standard topology), Keycloak on host. Verified: (1) **login works** — `testuser` and `creditor.demo` both get tokens, issued off Argon2id hashes in iam's `user_credentials`, via the SPI; a wrong password returns `invalid_grant`, not a token and not a 503; (2) **the registration chain runs** — register with no password → OTP by mail → `complete` returns `PROVISIONING` with no `keycloakUserId` and no synchronous iam call → async provisioning → **set-password email reaches the registrant** → password set → **new user logs in**; (3) the setup token is **single-use** (replay → 422); (4) the row ends at `UNVERIFIED` with a Keycloak id; (5) **the admin approval mail goes to `admin@ipie.gov.in`, not the registrant** — the exact defect of the superseded design, now correct. One bug found and fixed en route: `CREDENTIAL_VERIFY` was gated on the endpoint but never defined as a realm role nor granted to the SPI service account, so every verify returned 403. Now in `realm-export.json`; `IPIE_SECURITY_HMAC_KEY_SPI_TO_IAM` added to `docker-compose.services.yml`. |
| 2026-08-12 | (earlier that day) **First real run.** Keycloak realm re-imported (flows, policy, brute force all live and verified); SPI jar rebuilt and deployed, all 3 providers loaded. iam-service started in WSL and is healthy on 8093 with `V14`+`V15` applied and 11 seeded credential rows. A login attempt correctly returns `503 temporarily_unavailable` — the SPI's fail-closed path — but **cannot yet succeed**: Keycloak runs natively on Windows (`D:\keycloak-26.6.3`, `kc.bat`) and cannot reach WSL on either `localhost:8093` or the WSL IP `10.206.19.85:8093`; both time out. Zero requests reach iam. **This also means the pre-existing `PillarLinkResolverClient` SPI->iam call can never have worked from this Keycloak either.** Fix: run Keycloak in Docker/WSL like the rest of the stack, per `docker-compose.yml`. |
| 2026-08-12 | Bugs found *by running*, not by compiling: `V14` declared `token_hash CHAR(64)` while the entity maps `varchar(64)` — Hibernate schema validation refused to start (Postgres `bpchar`); fixed at source and the dev DB rolled back cleanly (iam to V13, user-service untouched at V29/299 rows). iam also needs `SERVER_PORT=8093`; it defaults to 8080 and collides with Keycloak. `/internal/credentials/verify` was missing from iam's HMAC `protected-paths` — added. |
| 2026-08-11 | Stage 2 built: `CredentialVerifyClient` (HMAC + client-credentials, mirroring `PillarLinkResolverClient`, tighter timeouts, no retry), `IpieValidatePassword`, `IpieUsernamePasswordForm` + factory, all registered; realm gains `ipie-browser` / `ipie-direct-grant` flows and points `browserFlow`/`directGrantFlow` at them. SPI jar builds clean. **Both authenticators fail closed** when iam is unreachable. Still unverified against a running Keycloak — in particular the `autheticatorFlow` misspelling in the realm representation, and whether brute-force counting still fires through the custom steps. |
| 2026-08-11 | Stages 1 and 3 largely built. iam: V14 schema, Argon2id, credential domain/persistence/service/controller, `CREDENTIAL_VERIFY`, public-paths + rate limits; the synchronous password endpoint and `AccountProvisioningClient` deleted; provisioning consumer now mints the setup token and publishes `ACCOUNT_CREDENTIAL_SETUP_REQUESTED` straight to comms. user-service: two-token work unwound, `setPassword` gone, tests reworked and passing. comms: new consumer + set-password email to the registrant, with two regression tests. All three services compile; user-service and comms unit tests green. **Never run.** Remaining: Stage 2 (SPI authenticators — until then no new account can log in), Stage 4 (the `/set-password` page the email links to does not exist), `CredentialService` tests, and deleting the now-unused `POST /internal/accounts`. |
| 2026-08-11 | (superseded row) Stage 1 started in ipie-iam-service: V14 schema, Argon2id `PasswordHasher`, `CredentialSetupTokens`, credential domain / repositories / persistence, `CredentialService`, DTOs, `CredentialController`, `CREDENTIAL_VERIFY` permission. `compileJava` green. **Not done:** `public-paths` + rate-limit config for the public set-password route, and tests. |
