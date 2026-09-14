# LetThemKnow

Multi-tenant notification / campaign platform (LINE + Email).
Spring Boot 3.3 · Java 21 · MySQL 8 · Redis 7 (Redisson) · React 18 + Vite.

See `CLAUDE.md` for engineering rules, `PLAN.md` for the phased delivery plan, and **`DEPLOY.md`** for the
step-by-step production setup: GCP free-tier e2-micro, Cloudflare Tunnel, and GitHub Actions.

## Local development

Prerequisites: Java 21, Docker Desktop, Node 20+, pnpm (`corepack enable && corepack prepare pnpm@9 --activate`).
Maven is not required; `api/mvnw` downloads it.

```bash
cp .env.example .env                       # adjust if needed
docker compose --profile infra up -d       # mysql (host port 3307) + redis (6379)

cd api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # API on http://localhost:8081
cd web && pnpm install && pnpm dev                                   # UI  on http://localhost:5173 (proxies /api -> 8081)
```

Local ports are deliberately non-default (`3307`, `8081`) so they do not collide with another MySQL or
Spring service on the same machine. Inside Docker the API listens on 8080 and MySQL on 3306.

The `local` profile also applies `V2__seed_dev.sql`: tenant `demo`, admin `admin@demo.local / Admin123!`,
and one API key (plaintext in the migration comment).

Smoke test:

```bash
curl -s http://localhost:8081/api/v1/health
# {"code":200,"message":"OK","data":{"status":"UP","time":"..."}}
```

### Authentication

| Client | Mechanism | Scope |
|---|---|---|
| Web console | `POST /api/v1/auth/login` → JWT (HS256, 12 h), sent as `Authorization: Bearer …` | everything except `/api/v1/integration/**` |
| ERP / external | `X-API-KEY: ltk_<8 char prefix>_<32 char secret>` | only `/api/v1/integration/**` |

```bash
TOKEN=$(curl -s -X POST localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@demo.local","password":"Admin123!"}' | jq -r .data.token)

curl -s localhost:8081/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8081/api/v1/api-keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"erp"}'      # plaintext key returned once
curl -s localhost:8081/api/v1/integration/ping -H 'X-API-KEY: ltk_...'
```

### Integration API (X-API-KEY)

Tenants get their own documentation in the console under **Developers** (`/developers`): base URL, auth,
copy-paste curl / Node / Python snippets pre-filled with their own key prefix and template names, the
response shape, and the error table. That is the page a tenant admin forwards to whoever writes the
integration. The machine-readable spec is split into two OpenAPI groups so an outside developer sees only
what applies to them:

| Group | Spec | Contains |
|---|---|---|
| Integration API (X-API-KEY) | `/api/docs/openapi/integration` | the 3 tenant endpoints |
| Console API (JWT) | `/api/docs/openapi/console` | everything the web console calls |

Swagger UI at `/api/docs` has a group selector for both. All doc paths are public, so an integrator can
read the spec before they hold a key.


```bash
# send one message immediately; templateName works too
curl -s -X POST localhost:8081/api/v1/integration/push/single \
  -H 'X-API-KEY: ltk_...' -H 'Content-Type: application/json' \
  -d '{"channel":"EMAIL","templateId":1,"recipient":"ann@example.com","params":{"name":"Ann","order":"A-42"}}'

curl -s localhost:8081/api/v1/integration/messages/1 -H 'X-API-KEY: ltk_...'
```

A delivery failure is reported in the body as `"status": "FAILED"` with an error code, not as an HTTP error;
the transactional message row is stored either way. Each key is limited to 60 requests per minute
(`ltk.integration.rate-limit-per-minute`); exceeding it returns 429 with `Retry-After: 60` and
`X-RateLimit-Limit`. Note that some HTTP clients (Apache HttpClient 5 among them) honour `Retry-After`
by sleeping and retrying automatically, which can make the rejection invisible to your code.

If the same email exists in several tenants, add `"tenant": "<tenant name>"` to the login body.

### Console API (JWT)

| Resource | Endpoints |
|---|---|
| Channels | `GET /api/v1/channels`, `POST /api/v1/channels/line`, `POST /api/v1/channels/smtp` (sends a test email first; 422 and nothing saved on failure) |
| Templates | `POST/GET /api/v1/templates`, `GET/PUT/DELETE /api/v1/templates/{id}`, `POST /api/v1/templates/{id}/preview` |
| Campaigns | `POST/GET /api/v1/campaigns`, `GET/PUT/DELETE /api/v1/campaigns/{id}`, `POST /api/v1/campaigns/{id}/upload-recipients` (multipart `file`), `GET /api/v1/campaigns/{id}/recipients?status=&page=&size=` |
| Lifecycle | `POST /api/v1/campaigns/{id}/publish` (optional `{"scheduledAt": "..."}`), `POST /api/v1/campaigns/{id}/abort`, `POST /api/v1/campaigns/{id}/retry-failed` |
| Monitoring | `GET /api/v1/campaigns/{id}/stats`, `GET /api/v1/campaigns/{id}/failures?errorCode=&page=&size=` |

Illegal lifecycle transitions return 409. IDs belonging to another tenant return 404, never 403, so tenant
membership is never leaked. Interactive docs live at `/api/docs` (spec at `/api/docs/openapi`).

Template payloads: EMAIL `{"html": "...", "text": "..."}` with a `subjectTemplate`; LINE `{"messages": [ ...1–5 LINE message objects... ]}`.
Placeholders use `{{param}}`; values are HTML-escaped in email HTML.

Recipient CSV: header row required, first column is the recipient (email or LINE user id), other columns become
template params. Max 20 MB / 200k rows. Duplicates and invalid identifiers are skipped; a new upload replaces the list.
Poll `GET /api/v1/campaigns/{id}` for `importStatus` (`IMPORTING` → `READY` | `FAILED`).

Identifiers are validated strictly: emails must match `^[^\s@]+@[^\s@]+\.[^\s@]+$`, and LINE user ids must match
`^U[0-9a-f]{32}$` (lowercase hex). In the console, the drop zone on the campaign page and in the wizard offers a
**Download sample CSV** button that generates a valid file whose columns are taken from that campaign's template,
so the header row always matches the `{{placeholders}}` the template actually uses.

### Dispatch engine (Redis Streams)

- Streams `ltk:dispatch:email` / `ltk:dispatch:line`, consumer group `dispatchers`, consumer `<hostname>-<n>`.
  Chunks: 50 recipients per email entry, 500 per LINE entry (multicast cap).
- Consumers claim rows `PENDING → SENDING`, send only the claimed rows, write per-recipient status, then ACK.
- Transient errors (SMTP 4xx / timeouts, LINE 429 / 5xx) re-enqueue via `ltk:dispatch:retry` with backoff
  30s · 2^attempt, max 3 attempts; terminal errors mark the row `FAILED`.
- `PendingReaper` runs every 60s and XAUTOCLAIMs entries idle > 5 min; rows a dead consumer left in `SENDING` are
  reset and re-sent, rows already `SENT`/`FAILED` are never sent twice.
- When no row is `PENDING`/`SENDING`, the campaign finalises once under lock `ltk:campaign:finalise:{id}`:
  `failed_count == 0 → COMPLETED`, otherwise `AWAITING_RESOLUTION`.
- Scheduled campaigns sit in the delayed queue `ltk:campaign:schedule`; a 60s sweep also starts overdue ones.
- LINE audience-group campaigns issue one narrowcast; progress is polled every 30s.
- `APP_WORKER_ENABLED=false` runs the API without consumers, pollers and the reaper.

Tuning lives under `ltk.dispatch.*` in `application.yml` (chunk sizes, `max-attempts`, `retry-base`, `reaper-idle`,
`consumers-per-stream`).

### Web console

React 18 + Vite + TypeScript, styled after the Google Cloud Console (Roboto, `#1a73e8`, hairline cards on a
`#f8f9fa` ground). Pages: Dashboard, Campaigns (list, 4-step wizard, detail with live counts and the
resolution drawer), Templates (editor with placeholder helper and live preview), Channels, API keys.
Times are displayed and entered in Asia/Taipei; the API stays in UTC.

New email templates start from one of six ready-made designs — announcement, order update, welcome,
minimal notice, receipt, security alert — defined in `web/src/lib/emailTemplates.ts`. They share one
builder, so every starter gets a 600px table layout with inline styles, a hidden preheader, an
Outlook-safe button, and mobile plus dark-mode blocks. There are no images anywhere: Gmail and Outlook
strip SVG, so the header is a text wordmark you change from "Your Company" to your own name. The preview
pane renders the real HTML and switches between desktop and mobile width.

LINE templates have three starters in `web/src/lib/lineTemplates.ts` — **Announcement** (a plain text
message), **Button card** (a `template`/`buttons` message with a URI action), and **Order card** (a Flex
bubble with detail rows and a footer button). They are real Messaging API message objects, so they respect
the limits that only bite at send time: a buttons `title` is capped at 40 characters and its `text` at 60
when a title is present, and every `template` or `flex` message carries the `altText` that LINE shows in
the chat list and the push notification. `LineMessagePreview` renders text, buttons and the Flex subset the
starters use as chat bubbles, so the JSON is authored with sight rather than blind.

All nine starters appear together in the gallery on the Templates page, badged by channel.

```bash
cd web
pnpm install
pnpm dev                 # http://localhost:5173, proxies /api → localhost:8081
pnpm build               # tsc + vite
pnpm gen:api             # regenerate src/api/schema.d.ts from openapi.json (curl localhost:8081/api/docs/openapi > openapi.json)
pnpm exec playwright install chromium
pnpm test:e2e            # smoke test: login → SMTP → template → wizard → publish → completed (needs API + Vite running)
```

The smoke test starts its own SMTP server on port 2525, so no real mail server is needed.

### Provisioning a tenant

The CLI runs on `ApplicationReadyEvent`, so it boots the whole application before inserting the rows and
exiting. Keep that in mind wherever you run it: it is a full instance, not a lightweight script.

```bash
# local (another API may already be on 8081, so pick a free port; workers off so this throwaway
# instance does not join the dispatchers consumer group)
java -jar api/target/letthemknow-api-*.jar --spring.profiles.active=local --server.port=0 \
  --ltk.worker.enabled=false \
  --provision-tenant --name=acme --admin-email=admin@acme.com --admin-password='S3cure-Pass!'
```

On the production VM the second instance does not fit beside the running one, so the API has to be
stopped for it. `DEPLOY.md` section 6 carries the three-line sequence and explains why each line is
there; follow it rather than the command above.

Run the full verification before declaring a phase done:

```bash
cd api && ./mvnw -q verify        # unit + Testcontainers integration tests
cd web && pnpm build
```

## Deployment status

Live on a GCP e2-micro behind a Cloudflare Tunnel, deployed by GitHub Actions on every push to `main`.

| | |
|---|---|
| Console | `https://letthemknow.hkwprince.com` |
| Integration API | `https://letthemknowapi.hkwprince.com` |
| API boot time on that VM | ~6 min (19 s on a laptop; the machine is a quarter of a shared vCPU) |
| Steady-state memory | 464 MB of 958 MB |

Before publishing this repository or changing anything on the VM, read `DEPLOY.md`. It carries the
audit checklist, the measured numbers behind the health-check windows, and `scripts/check-secrets.sh`,
which blocks credentials from reaching a tracked file.

## Deployment (single GCP VM + Cloudflare Tunnel)

Images: `ghcr.io/hkwprince/letthemknow-api` and `ghcr.io/hkwprince/letthemknow-web`, tagged `sha-<short>` and
`latest`. The owner segment is lower-cased by the workflow, because registries reject capitals.
`deploy.yml` builds and pushes on every push to `main`, then SSHes to the VM and runs
`docker compose pull && docker compose up -d --remove-orphans`.

### GitHub repository secrets

| Secret | Purpose |
|---|---|
| `VM_HOST` | VM public IP / hostname for SSH |
| `VM_USER` | SSH user with docker permissions |
| `VM_SSH_KEY` | private key for `VM_USER` |
| `VM_APP_DIR` | directory on the VM containing `docker-compose.yml` and `.env` (e.g. `/opt/letthemknow`) |

`GITHUB_TOKEN` (built-in) pushes to GHCR. Because the packages are private, the VM must `docker login
ghcr.io` once with a personal access token **(classic)** carrying `read:packages`; fine-grained tokens are
rejected by GHCR.

### VM bootstrap

Ubuntu 22.04 on a 1 GB e2-micro. The stack fits only because every service in `docker-compose.yml` is
memory-capped and the VM has a 2 GB swapfile. **`DEPLOY.md` is the runbook** — VM creation, Docker, swap,
the GHCR login, the GitHub secrets, the Cloudflare Tunnel and its two routes, the `.env` template, the
first deploy, troubleshooting and backups. Follow it rather than improvising from this section.

No ports are exposed to the internet. `cloudflared` dials out and routes `letthemknow.hkwprince.com` to
`web:80` and `letthemknowapi.hkwprince.com` to `api:8080` over the Docker network.
