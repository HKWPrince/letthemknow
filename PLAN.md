# LetThemKnow — Phased Delivery Plan

Rules in `CLAUDE.md` apply to every phase. Stop after each phase for review.

---

## Phase 0 — Scaffold & infrastructure
**Goal:** empty app boots against real MSSQL + Redis in Docker and deploys to the VM.

- [x] `api/pom.xml` (Boot 3.3.x, Java 21, deps listed in CLAUDE.md §2), `LetThemKnowApplication`
- [x] `application.yml` + `local` / `prod` profiles, env-driven config, `ddl-auto=validate`, Flyway enabled
- [x] `V1__init.sql` — PRD schema with these changes:
  - `campaign_recipients.tenant_id BIGINT NOT NULL`, index `(tenant_id, campaign_id, status)`
  - `campaigns.import_status NVARCHAR(20) NOT NULL DEFAULT 'NONE'`, `import_error NVARCHAR(MAX)`
  - `campaign_recipients.status` values: `PENDING|SENDING|SENT|FAILED|CANCELLED`
  - new table `transactional_messages(id, tenant_id, channel_type, template_id, recipient_identifier, payload_params JSON, status, error_code, error_message, external_message_id, created_at, sent_at)`
  - `api_keys.last_used_at DATETIMEOFFSET NULL`
  - `users.status`, `tenants.status` CHECK constraints
- [x] `V2__seed_dev.sql` (local-only location)
- [x] `docker-compose.yml` with profiles `infra` (mssql, redis) and default (all + cloudflared); healthchecks; named volumes
- [x] `api/Dockerfile`, `web/Dockerfile` + `nginx.conf` (SPA fallback, `/api/` proxy)
- [x] `web/` Vite + TS + Tailwind + shadcn init, `pnpm build` passes
- [x] `.github/workflows/ci.yml` (mvn verify with Testcontainers, pnpm build) and `deploy.yml`
- [x] `README.md` with VM bootstrap steps (install docker, create `.env`, cloudflared token)
- [x] Smoke test: `GET /api/v1/health` returns `{code:200,...}`

**Accept:** `docker compose up` on a clean machine serves the SPA and health endpoint through the tunnel.

---

## Phase 1 — Entities, DTOs, security, tenant context
- [x] `TenantAwareEntity` base + `@FilterDef/@Filter` on all tenant tables; `TenantContextHolder`, `TenantContextFilter`, `TenantFilterAspect` (enables Hibernate filter per `EntityManager` in the request/worker scope), `SystemTenantScope`
- [x] Entities: `Tenant`, `User`, `ApiKey`, `ChannelConfig`, `MessageTemplate`, `Campaign`, `CampaignRecipient`, `TransactionalMessage`; enums for every status/type column (`@Enumerated(STRING)`)
- [x] Repositories with the queries later phases need (`findByCampaignIdAndStatus`, count-by-status projection, `claimPending` bulk update, `findByPrefix`)
- [x] `AesGcmEncryptor` + `EncryptedStringConverter` (`@Convert`) on the three secret columns; unit tests incl. tamper detection
- [x] Security: `SecurityConfig` (stateless, CSRF off, CORS for local dev), `JwtService`, `JwtAuthFilter`, `ApiKeyAuthFilter` (scoped to `/api/v1/integration/**`), `PasswordEncoder` (BCrypt), `AuthController` (`POST /auth/login`, `GET /auth/me`)
- [x] `ApiKeyService.create()` returns plaintext once; `POST /api-keys`, `GET /api-keys`, `DELETE /api-keys/{id}` (revoke)
- [x] `ApiResponse`, `ErrorCode`, `GlobalExceptionHandler`, validation error shape
- [x] DTO records + MapStruct mappers for all resources
- [x] Tenant provisioning CLI: `java -jar app.jar --provision-tenant --name=X --admin-email=Y --admin-password=Z` (`ApplicationReadyEvent` listener, exits after)
- [x] Tests: tenant filter isolation (two tenants, cross-read returns empty), JWT round-trip, API key auth accepted/rejected, encryption converter round-trip through JPA

**Accept:** login works; a query from tenant A can never return tenant B rows (test proves it); secrets are ciphertext in the DB.

---

## Phase 2 — Core services & channel integrations
- [x] `ChannelConfigService`: upsert LINE / SMTP config, `POST /channels/line`, `POST /channels/smtp` (sends test email synchronously; 422 on failure, nothing persisted), `GET /channels` (masked)
- [x] `DynamicMailSenderFactory` (Caffeine cache keyed by tenantId, invalidate on save), `EmailSender.send(tenantId, to, subject, html, text)`
- [x] `LineApiClient` (`RestClient`, bearer from decrypted token, typed request/response records, error mapping to `LineApiException{status, body}`), `LineSender.multicast/push/narrowcast/progress`
- [x] `TemplateRenderer` + `MessageTemplateService`; CRUD `/templates`; validation that payload matches channel shape
- [x] `CampaignService`: CRUD `/campaigns` (list paged, get with counts), `CampaignStateMachine` with unit tests for every legal/illegal transition
- [x] `RecipientImportService`: multipart CSV → async parse → JDBC batch insert, `import_status` updates, `GET /campaigns/{id}/recipients?status=&page=`
- [x] `DispatchErrorClassifier` with tests for SMTP and LINE cases
- [x] Tests: GreenMail SMTP round-trip via dynamic sender; WireMock LINE multicast/narrowcast; CSV import of 10k rows with duplicates

**Accept:** saving SMTP config delivers a real test email; templates render; CSV import populates recipients.

---

## Phase 3 — Dispatch engine (Redis Stream + Redisson)
- [x] `DispatchStreams` (Redisson starter config), stream/group bootstrap on startup (`XGROUP CREATE ... MKSTREAM` idempotent)
- [x] `CampaignScheduler`: `RDelayedQueue` offer/cancel; `SchedulePoller` (runs under `SystemTenantScope`, only when `APP_WORKER_ENABLED`) + 60s overdue sweep
- [x] `CampaignRunner.start(id)`: `SCHEDULED/DRAFT → PROCESSING`, sets `total_count`, chunks `PENDING` recipient IDs into stream messages (email 50 / LINE 500); for `LINE_AUDIENCE_GROUP` issues one narrowcast and registers with `NarrowcastProgressPoller`
- [x] `DispatchConsumer` (one thread per stream, `XREADGROUP` blocking): claim rows → set tenant context → render → send → per-recipient status/error → ACK → `CampaignProgressService.onChunkDone`
- [x] Retry re-enqueue with backoff via delayed queue `ltk:dispatch:retry`; `PendingReaper` with `XAUTOCLAIM`
- [x] `CampaignProgressService` finalisation under Redisson lock; counts updated with a single aggregate `UPDATE campaigns SET ... FROM (SELECT ...)` statement
- [x] Abort handling and `CANCELLED` sweep; retry-failed handling (`FAILED → PENDING`, `retry_count+1`, re-chunk, state `RETRYING`)
- [x] Graceful shutdown: consumers stop reading, in-flight chunk finishes, unACKed entries left for reaper
- [x] Tests (Testcontainers Redis + MSSQL, GreenMail/WireMock): 1k-recipient email campaign completes with correct counts; injected transient failure retries then succeeds; terminal failure → `AWAITING_RESOLUTION`; abort mid-run leaves no `PENDING`; consumer crash simulation → reaper recovers chunk without duplicate sends

**Accept:** all Phase 3 integration tests green; no double-send under crash test.

---

## Phase 4 — REST API completion & human-in-the-loop
- [x] `POST /campaigns/{id}/publish` (body optional `scheduledAt`; past/absent → start now)
- [x] `POST /campaigns/{id}/retry-failed`, `POST /campaigns/{id}/abort`
- [x] `GET /campaigns/{id}/failures?page=` (FAILED recipients with error code/message, filterable by `errorCode`), `GET /campaigns/{id}/stats`
- [x] `POST /api/v1/integration/push/single` (X-API-KEY), `GET /api/v1/integration/messages/{id}`; updates `api_keys.last_used_at`
- [x] OpenAPI docs at `/api/docs` (JWT + API key security schemes)
- [x] Rate limit on integration endpoints: Redisson `RRateLimiter` per API key, 60 req/min (429 on exceed)
- [x] Controller integration tests for every endpoint, including 409 on illegal transitions and 403 on cross-tenant IDs (must return 404, not leak existence)

**Accept:** full lifecycle drivable via curl; ERP single push works with API key only.

---

## Phase 5 — React console
- [x] Auth: login page, token in memory + `localStorage`, fetch client with 401 → logout, route guard
- [x] Layout with sidebar: Dashboard, Campaigns, Templates, Channels, API Keys (Google Cloud Console styling)
- [x] Channels page: LINE form, SMTP form (masked secrets, "send test" feedback)
- [x] Templates page: list + editor (email: subject + HTML/text with preview; LINE: JSON editor with validation and placeholder helper)
- [x] Campaign wizard (4 steps): Basics (title, channel, template) → Audience (CSV upload with import progress polling, or LINE audience group ID) → Schedule (now / datetime in Asia/Taipei) → Review & Publish
- [x] Campaign detail: status badge, live counts (poll every 5s while processing), abort button, **Resolution Drawer** shown in `AWAITING_RESOLUTION` listing failed recipients with error code/message, filter by code, "Retry failed" and "Terminate" actions with confirm dialogs
- [x] API Keys page: create (show plaintext once), revoke
- [x] Dashboard: recent campaigns + totals
- [x] Error toasts from `ApiResponse.message`; loading/empty states; TypeScript types generated from OpenAPI (`openapi-typescript`)
- [x] `pnpm build` clean, Playwright smoke test: login → create campaign → publish

**Accept:** an operator can complete the whole flow from the browser without touching the API directly.

---

## Out of scope (v1)
Refresh tokens, multi-role RBAC, LINE inbound webhooks, unsubscribe/suppression lists,
per-tenant SMTP throttling, i18n, separate worker deployment, key rotation.
