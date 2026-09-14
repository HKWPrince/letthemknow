# LetThemKnow — Claude Code Project Guide

LetThemKnow is a multi-tenant notification/campaign platform (LINE + Email).
Read this file fully before touching code. `PLAN.md` holds the phased task list;
work one phase at a time and stop for review at each phase boundary.

## 1. Repository layout (single Maven module + Vite app)

```
letthemknow/
├── CLAUDE.md, PLAN.md
├── docker-compose.yml            # mysql, redis, api, web, cloudflared
├── .github/workflows/
│   ├── ci.yml                    # build + test on PR
│   └── deploy.yml                # build images → GHCR → SSH to VM → compose up
├── api/                          # Spring Boot 3.3+, Java 21, Maven
│   ├── pom.xml
│   ├── Dockerfile                # multi-stage, jlink not needed, eclipse-temurin:21-jre
│   └── src/main/java/io/letthemknow/
│       ├── LetThemKnowApplication.java
│       ├── config/               # Security, Redis, Redisson, Jackson, OpenAPI
│       ├── common/               # ApiResponse, exceptions, ErrorCode, crypto, tenant context
│       ├── tenant/               # Tenant entity + provisioning
│       ├── auth/                 # JWT, API key, login endpoint
│       ├── channel/              # ChannelConfig, DynamicMailSenderFactory, LineApiClient
│       ├── template/             # MessageTemplate + rendering
│       ├── campaign/             # Campaign, Recipient, state machine, CSV import
│       ├── dispatch/             # Redis Stream producers/consumers, Redisson scheduler
│       └── integration/          # X-API-KEY external endpoints, transactional_messages
│   └── src/main/resources/
│       ├── application.yml, application-local.yml, application-prod.yml
│       └── db/migration/V1__init.sql ...   # Flyway, MySQL DDL
│   └── src/test/                 # Testcontainers (mysql:8.0, redis:7)
└── web/                          # React 18 + Vite + TS + Tailwind + shadcn/ui
    ├── Dockerfile                # build → nginx:alpine serving /usr/share/nginx/html
    └── src/{api,components,features,pages,lib}
```

## 2. Stack & versions
- Java 21, Spring Boot 3.3.x, Spring Security 6, Spring Data JPA (Hibernate 6), Flyway
- MySQL 8 (`mysql-connector-j`), Redis 7 via Redisson (RDelayedQueue + RStream)
  - **Deviation from the PRD, deliberate.** The PRD specifies MS SQL Server 2022. Measured, the MSSQL
    container holds 1.19 GB — more than the whole 1 GB e2-micro free-tier VM this deploys to, so it
    could never run there. MySQL 8 tuned measures 132 MB. Timestamps are DATETIME(6) holding UTC with
    Hibernate `timezone.default_storage=NORMALIZE_UTC`, since MySQL has no timezone-aware type.
- Lombok, MapStruct (DTO mapping), Jackson, springdoc-openapi
- Testing: JUnit 5, Testcontainers, WireMock (LINE API), GreenMail (SMTP)
- Frontend: React 18, Vite 5, TypeScript strict, Tailwind, shadcn/ui, TanStack Query, react-router, zod, react-hook-form
- Node 20, pnpm

## 3. Non-negotiable engineering rules
1. **Tenant isolation**: every tenant-scoped entity extends `TenantAwareEntity`
   (`tenant_id` column + Hibernate `@FilterDef(name="tenantFilter")`). `TenantContextHolder`
   is a ThreadLocal set by `TenantContextFilter` (from JWT or API key) and cleared in `finally`.
   `TenantFilterAspect` (or an `EntityManager` interceptor) enables the filter on every repository
   call. Workers set the tenant context explicitly from the stream message before touching JPA.
   **`campaign_recipients` and `transactional_messages` carry `tenant_id` too.**
2. **Never** disable the tenant filter except in `SystemTenantScope.runAsSystem(...)`, used only by
   the scheduler poller and the tenant-provisioning CLI.
3. **Secrets**: LINE secret/token and SMTP password are stored via `AesGcmEncryptor`
   (AES-256-GCM, 12-byte random IV, 128-bit tag, output `v1:<base64(iv||ct||tag)>`).
   Master key: env `LTK_MASTER_KEY` (base64, 32 bytes). Never log decrypted values.
   DTO responses return `hasSecret: true` / masked values, never plaintext.
4. **Auth**:
   - Web: `POST /api/v1/auth/login` → JWT (HS256, secret `LTK_JWT_SECRET`, 12h TTL, claims: `sub`=userId, `tid`, `role`, `email`). No refresh tokens in v1.
   - External: `X-API-KEY: ltk_<8 char prefix>_<32 char random>`. Store SHA-256 hash; look up by prefix, then constant-time compare. Only `/api/v1/integration/**` accepts API keys; everything else requires JWT.
   - Roles: only `ADMIN` in v1. Keep `role` column; `@PreAuthorize("hasRole('ADMIN')")` on mutating endpoints.
5. **Responses**: all controllers return `ApiResponse<T> { code, message, data }`. Errors go through
   `GlobalExceptionHandler` → `{ code: <ErrorCode>, message, data: null }`. HTTP status mirrors code (400/401/403/404/409/422/500).
6. **State machine**: transitions live only in `CampaignStateMachine.transition(campaign, event)`; throw `IllegalStateTransitionException` (409) otherwise. Allowed:
   ```
   DRAFT → SCHEDULED | PROCESSING | TERMINATED
   SCHEDULED → PROCESSING | TERMINATED
   PROCESSING → COMPLETED | AWAITING_RESOLUTION | TERMINATED
   AWAITING_RESOLUTION → RETRYING | TERMINATED
   RETRYING → COMPLETED | AWAITING_RESOLUTION | TERMINATED
   ```
   `RETRYING` *is* a processing state. Completion rule: `failed_count == 0 ? COMPLETED : AWAITING_RESOLUTION`.
7. **Dispatch (Redis Stream)**:
   - Streams: `ltk:dispatch:email`, `ltk:dispatch:line`. Group: `dispatchers`. Consumer name: `${hostname}-${n}`.
   - Message = `{ tenantId, campaignId, channel, recipientIds: long[], attempt }` (JSON). Chunk size: email 50, LINE 500 (multicast cap).
   - Consumer must `UPDATE campaign_recipients SET status='SENDING' WHERE id IN (...) AND status='PENDING'` and only send the rows actually claimed → at-least-once delivery without double-send.
   - ACK after DB status write. A `PendingReaper` (`@Scheduled` every 60s) `XAUTOCLAIM`s entries idle > 5 min.
   - Error classification (`DispatchErrorClassifier`): TRANSIENT (SMTP timeouts/4xx-421/450/451, LINE 429/5xx) → re-enqueue with `attempt+1`, backoff 30s·2^attempt via RDelayedQueue, max 3 attempts; TERMINAL (invalid address, LINE 400 invalid user, 401/403 config) → `FAILED` immediately. Exhausted retries → `FAILED`.
   - After each chunk, `CampaignProgressService.onChunkDone(campaignId)` recomputes counts; when `pending+sending == 0` it finalises via the state machine (once, guarded by an Redisson lock `ltk:campaign:finalise:{id}`).
   - Abort: set `TERMINATED`; consumers check campaign status before sending each chunk and mark remaining `PENDING` → `CANCELLED`.
8. **Scheduling**: `RDelayedQueue<Long>` over `RBlockingQueue` `ltk:campaign:schedule`. On publish with future `scheduled_at` → offer with delay; `SchedulePoller` takes and starts `CampaignRunner.start(id)` (verifies status still `SCHEDULED`; ignore stale entries).
9. **Email**: `DynamicMailSenderFactory` caches `JavaMailSenderImpl` per tenant in a Caffeine cache (30 min, invalidated on config save). Test email on SMTP save is synchronous; failure → 422 and config is *not* saved.
10. **LINE**: `LineApiClient` (Spring `RestClient`) wraps `/v2/bot/message/multicast`, `/v2/bot/message/push`, `/v2/bot/message/narrowcast`, `/v2/bot/message/progress/narrowcast`. Audience types: `CSV_LIST` (user IDs from CSV, multicast) and `LINE_AUDIENCE_GROUP` (`target_audience_meta = {"audienceGroupId": ...}`, one narrowcast request, progress polled every 30s by `NarrowcastProgressPoller`; `total_count` filled from the progress response).
11. **Templates**: `content_payload` JSON. Email: `{ "html": "...", "text": "..." }`; LINE: `{ "messages": [ ...LINE message objects... ] }` (max 5). Placeholders `{{param}}` rendered with a small safe Mustache-style replacer (`TemplateRenderer`); HTML-escape params for email HTML.
12. **CSV import**: `POST /campaigns/{id}/upload-recipients` accepts ≤ 20 MB / 200k rows, header row required, first column = `recipient` (email or LINE userId), remaining columns → `payload_params` JSON. Dedupe on `recipient_identifier`. Parsed on a `@Async` executor; progress in `campaigns.import_status` (`NONE|IMPORTING|READY|FAILED`) + `import_error`. Bulk insert via JDBC batch (500 rows). Only allowed in `DRAFT`.
13. **Single push**: `POST /api/v1/integration/push/single` `{ channel, templateId|templateName, recipient, params }` → inserts `transactional_messages` row, sends synchronously, returns `{ messageId, status }`. No campaign row.
14. **Time**: store `DATETIME(6)` holding UTC, map to `OffsetDateTime` with Hibernate `timezone.default_storage=NORMALIZE_UTC`, API in ISO-8601 UTC. MySQL has no timezone-aware type, so the offset lives in the mapping, not the column — never write a local-time value into one of these columns. Server timezone UTC; UI displays Asia/Taipei.
15. **Code quality**: no TODO stubs in core paths, constructor injection, `record` DTOs with Jakarta validation, Hibernate `ddl-auto=validate`, package-private where possible, integration test per controller with Testcontainers. Run `mvn -q verify` and `pnpm build` before declaring a phase done.

## 4. Environment variables (prod)
```
LTK_MASTER_KEY, LTK_JWT_SECRET
DB_URL=jdbc:mysql://mysql:3306/letthemknow?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false
DB_USER, DB_PASSWORD, MYSQL_ROOT_PASSWORD
REDIS_URL=redis://redis:6379
APP_WORKER_ENABLED=true          # same process runs API + workers in v1
CLOUDFLARE_TUNNEL_TOKEN
```

## 5. Deployment (single GCP VM + Cloudflare Tunnel)
- Images pushed to `ghcr.io/<owner>/letthemknow-api` and `-web`, tagged `sha-<short>` and `latest`.
- `deploy.yml`: on push to `main` → build/push → `appleboy/ssh-action` to VM → `docker compose pull && docker compose up -d --remove-orphans`.
- No ports exposed to the internet; `cloudflared` container routes `app.<domain>` → `web:80`, and nginx proxies `/api/` → `api:8080`.
- VM sizing note: `mysql:8.0` tuned (128 MB buffer pool, performance_schema off) measures ~132 MB, which is
  what makes the 1 GB e2-micro free tier viable. The JVM is then the largest consumer, so set a container
  memory limit — with no limit the api container sized its heap to the host and reached 610 MB.

## 6. Local dev
- `docker compose --profile infra up` → mysql + redis only. `mvn spring-boot:run -Dspring-boot.run.profiles=local`, `pnpm dev` (Vite proxy `/api` → 8080).
- `V2__seed_dev.sql` (local profile only via Flyway `locations` override): tenant `demo`, admin `admin@demo.local / Admin123!`, one API key printed in migration comment.

## 7. Working style for Claude Code
- Before each phase: read PLAN.md, restate the checklist, then implement.
- After each phase: run tests, list files changed, note any deviation from this document, stop and wait for approval.
- If the PRD and this file conflict, this file wins; call out the conflict.
