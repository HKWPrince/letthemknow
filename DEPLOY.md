# Deploying LetThemKnow

Target: one **GCP e2-micro** (Always Free, `us-west1`), fronted by **Cloudflare Tunnel**, deployed by
**GitHub Actions** on every push to `main`.

Two public hostnames, both served from the same VM:

| Hostname | Goes to | Used by |
|---|---|---|
| `letthemknow.hkwprince.com` | `web:80` (nginx serves the SPA and proxies `/api/` to the API) | your operators |
| `letthemknowapi.hkwprince.com` | `api:8080` | your tenants' ERP systems, with `X-API-KEY` |

**No inbound HTTP ports are opened.** `cloudflared` dials *out* to Cloudflare and traffic comes back down
that connection, so the VM needs no HTTP firewall rule and has no public web port to attack. Port 22 is
the only thing exposed, key-only, and only so GitHub Actions can deploy.

> Dashboard labels in GCP, Cloudflare and GitHub change from time to time. The nouns are stable even when
> the exact menu path is not. The Cloudflare steps below match Cloudflare's documentation as of
> September 2026.

## Before you start

- `hkwprince.com` already added to Cloudflare, with its nameservers pointing there. Everything in step 4
  depends on this; the domain dropdown will be empty otherwise.
- A GCP project with billing enabled. Billing must be on even to use the free tier.
- The repository pushed to GitHub, since Actions builds the images.

---

## Memory: read this before you start

An e2-micro has **1 GB of RAM**, and the stack only fits because `docker-compose.yml` caps every service.
Measured locally with those caps in place, after sending 500 emails through a real campaign:

| Container | Used | Cap |
|---|---|---|
| api (JVM) | 412 MB | 420 MB |
| mysql | 139 MB | 256 MB |
| redis | 10 MB | 64 MB |
| web (nginx) | 8 MB | 32 MB |
| **Total** | **568 MB** | |

Nothing was OOM-killed and no container restarted. Add roughly 250 MB for Ubuntu and the Docker daemon,
plus ~35 MB for `cloudflared`, and you land near 850 MB of 1024 MB. That is tight but workable — **the
2 GB swapfile in step 2 is not optional.** If you ever remove the `mem_limit` lines, the JVM will size its
heap to the whole machine (measured at 627 MB unbounded) and the kernel will start killing containers.

---

## 1. Create the VM

In the GCP console, **Compute Engine → VM instances → Create instance**:

| Setting | Value | Why |
|---|---|---|
| Name | `letthemknow` | |
| Region / Zone | **`us-west1`** (e.g. `us-west1-b`) | Always Free covers only `us-west1`, `us-central1`, `us-east1`. Not Asia. |
| Machine type | **`e2-micro`** | The only free shape. |
| Boot disk | Ubuntu 22.04 LTS, **30 GB standard persistent disk** | Free tier allows 30 GB-months of *standard* PD. Balanced/SSD is charged. |
| Firewall | leave both HTTP boxes **unchecked** | The tunnel is outbound. You do not need them. |

Leave the external IP as Ephemeral. The VM needs outbound internet for the tunnel and image pulls, and
inbound SSH for deploys.

### What "free" actually covers

From Google's Always Free documentation: **one non-preemptible e2-micro per month** in `us-west1`,
`us-central1` or `us-east1`; **30 GB-months of standard persistent disk**; and **1 GB/month of outbound
data transfer from North America**, excluding China and Australia.

**The external IPv4 address is not free, and it is the one charge this deployment cannot avoid.** Google
began charging for in-use external IPv4 addresses attached to VM instances on 1 February 2024, at
**USD 0.005 per hour**, which is about **USD 3.65 per month**. There is no free-tier exemption. The VM
itself is free; its address is not.

Two consequences. First, budget for a few dollars a month rather than zero. Second, **reserve a static
address** rather than keeping the ephemeral one. In-use static and in-use ephemeral addresses cost the
same, so a static address is free of penalty while it stays attached, and it stops the IP changing every
time the VM is stopped and started. An ephemeral address that changes silently breaks the `VM_HOST` secret
and every deploy after it. Only an *unattached* reserved address is billed at a higher rate, so release it
if you ever delete the VM.

Set the rest of your expectations from your own invoice:

**Billing → Budgets & alerts → Create budget**, scope it to this project, set the amount to something
small like USD 5, and enable email alerts at 50% and 100%. Then check **Billing → Reports** after a few
days. If any line item appears, it will be small and you will know precisely what it is — far better than
discovering it after a month.

Egress is the other thing to watch: 1 GB/month is not much if the console is used heavily, since tunnel
traffic leaves the VM. Campaign email goes out over SMTP and counts too.

SSH into it from the console (**SSH** button on the instance row) for the next step.

## 2. Prepare the VM

```bash
# Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker

# 2 GB swap — required, see the memory section above
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h    # confirm Swap shows 2.0Gi

# App directory
sudo mkdir -p /opt/letthemknow && sudo chown $USER /opt/letthemknow
```

### Get `docker-compose.yml` onto the VM

The repository is **private**, so `raw.githubusercontent.com` answers 404 to an anonymous `curl`. Copy the
file up from your laptop instead — one command, no token:

```bash
# on your laptop, from the project root
gcloud compute scp docker-compose.yml letthemknow:/opt/letthemknow/ --zone=us-west1-b
```

If `gcloud` is not installed on your laptop, use the **browser SSH window's own upload button**: open the
SSH session from the instance row, click the gear icon at the top right, choose **Upload file**, pick
`docker-compose.yml`, and then move it into place, since the upload lands in your home directory:

```bash
mv ~/docker-compose.yml /opt/letthemknow/
```

Do **not** try to `curl` it from the GitHub API with the registry token from the next section. That token
carries `read:packages` only, which grants no access to repository contents, so the request returns 404 and
looks exactly like a missing file.

This file is the only thing the VM needs from the repository. The application itself arrives as images
from GHCR, never as source.

### Let the VM pull your private images

Your images are private on GHCR, so the VM needs read access once. The credential is a GitHub **personal
access token (classic)** that you create yourself; it is not the tunnel token and not the deploy SSH key.

1. On github.com, click your avatar (top right) → **Settings** → scroll to the bottom of the left sidebar
   → **Developer settings** → **Personal access tokens** → **Tokens (classic)** → **Generate new token
   (classic)**.
2. Name it something like `letthemknow-vm-pull`, set an expiry you will actually remember, and tick
   **only** `read:packages`. Nothing else. Generate, then copy the `ghp_…` value — GitHub shows it once.
3. On the VM, replacing both placeholders with the token and your GitHub username:

```bash
echo 'ghp_PASTE_YOUR_TOKEN_HERE' | docker login ghcr.io -u HKWPrince --password-stdin
# Login Succeeded
```

> **Use the classic token, not a fine-grained one.** GitHub's own Packages documentation states that
> "GitHub Packages only supports authentication using a personal access token (classic)". A fine-grained
> token is rejected by `ghcr.io` with `denied` or `unauthorized`, which reads exactly like a wrong password
> and will cost you an hour if you do not know this.

That writes `~/.docker/config.json` and persists across reboots. A read-only token is deliberate: a VM
that is compromised cannot push a poisoned image back to your registry.

## 3. Give GitHub Actions a way in

On your **laptop**, make a deploy key pair (no passphrase, since a workflow cannot type one). The
comment at the end **must be the Linux username you want the key to belong to**, because Compute
Engine parses the username out of that comment:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/letthemknow_deploy -C "prince880211" -N ""
cat ~/.ssh/letthemknow_deploy.pub    # public half, goes in metadata below
cat ~/.ssh/letthemknow_deploy        # private half, goes in the VM_SSH_KEY secret
```

> ### Do not append the key to `~/.ssh/authorized_keys`
>
> It appears to work, then breaks days later, and the failure looks like a rejected credential rather
> than a deleted one. Google's documentation is explicit: *"Public SSH keys that you add directly to a
> VM's `~/.ssh/authorized_keys` files might be overwritten by the VM's guest agent."* Worse, *"if you
> manually added SSH keys to your VM and then connected to your VM using the Google Cloud console,
> Compute Engine created a new key pair for your connection. After the new key pair expired, Compute
> Engine deleted your `~/.ssh/authorized_keys` file"* — taking your deploy key with it.
>
> Using the browser SSH button, which this runbook tells you to do, is therefore enough to arm that
> deletion. **This happened to this deployment**: the key worked, then Actions failed the next day with
> `unable to authenticate, attempted methods [none publickey]`.

Add the public half to **instance metadata** instead, which the guest agent maintains for you and which
survives reboots and console logins. In the GCP console: **Compute Engine → VM instances →
`letthemknow` → Edit → SSH Keys → Add item**, then paste the entire contents of
`letthemknow_deploy.pub` and save. The username shown beside it must read `prince880211`; if it shows
something else, the comment on the key is wrong, so fix the comment and re-paste.

Verify from your laptop before moving on, since every later step depends on it:

```bash
ssh -i ~/.ssh/letthemknow_deploy prince880211@<VM_HOST> 'echo ok'
```

In the repo, **Settings → Secrets and variables → Actions**:

| Type | Name | Value |
|---|---|---|
| Secret | `VM_HOST` | the VM's external IP |
| Secret | `VM_USER` | your Linux username on the VM |
| Secret | `VM_SSH_KEY` | the **private** key, whole file including the BEGIN/END lines |
| Secret | `VM_APP_DIR` | `/opt/letthemknow` |
| Variable | `PUBLIC_API_URL` | `https://letthemknowapi.hkwprince.com/api/v1` |

`PUBLIC_API_URL` is a **variable, not a secret**: it is baked into the JavaScript bundle at build time, so
it is public by nature, and the Developers page uses it to tell integrators which host to call.

## 4. Create the Cloudflare Tunnel

In the Cloudflare dashboard, go to **Networking → Tunnels → Create a tunnel**, give it a name such as
`letthemknow`, and select **Create Tunnel**.

Cloudflare then shows an install command for your operating system. **Do not run it** — we run cloudflared
as a container instead. Copy only the long token out of that command, the value after `--token`, and put
it in `CLOUDFLARE_TUNNEL_TOKEN` in step 5. The dashboard will keep showing "waiting for connection" until
the container starts in step 6, which is expected.

Then open the tunnel and, on its **Routes** tab, select **Add route → Published application** twice:

| Subdomain | Domain | Service URL |
|---|---|---|
| `letthemknow` | `hkwprince.com` | `http://web:80` |
| `letthemknowapi` | `hkwprince.com` | `http://api:8080` |

`web` and `api` are the **compose service names**, not hostnames you own. cloudflared resolves them over
the Docker network, which is exactly why no ports are published to the host. The DNS records are created
for you; there is nothing to add by hand.

## 5. Write the VM's `.env`

```bash
cd /opt/letthemknow
cat > .env <<'EOF'
COMPOSE_PROFILES=app
# Lower-case. Registries reject capitals, and the deploy workflow lower-cases the owner when it
# pushes, so `GHCR_OWNER=HKWPrince` here makes every `docker compose pull` fail.
GHCR_OWNER=hkwprince
IMAGE_TAG=latest

# Secrets — the loop below fills these. Never paste a real value into this file: it is tracked by git.
LTK_MASTER_KEY=
LTK_JWT_SECRET=

MYSQL_ROOT_PASSWORD=
DB_URL=jdbc:mysql://mysql:3306/letthemknow?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false
DB_USER=letthemknow
DB_PASSWORD=

REDIS_URL=redis://redis:6379
APP_WORKER_ENABLED=true

# Shared code for self-service signup at /signup. EMPTY means signup is disabled, never open.
LTK_SIGNUP_CODE=

CLOUDFLARE_TUNNEL_TOKEN=
EOF

# Fill the four secrets
for k in LTK_MASTER_KEY LTK_JWT_SECRET MYSQL_ROOT_PASSWORD DB_PASSWORD; do
  sed -i "s|^$k=.*|$k=$(openssl rand -base64 32)|" .env
done
# Then paste the tunnel token into CLOUDFLARE_TUNNEL_TOKEN=
chmod 600 .env
```

**`LTK_MASTER_KEY` encrypts every tenant's SMTP password and LINE token.** If you lose it, those secrets
are unrecoverable and each tenant must re-enter them. Back it up somewhere safe now.

## 6. First deploy

Push to `main`, or run the **Deploy** workflow manually from the Actions tab. It builds both images,
pushes them to GHCR, SSHes in, pulls, restarts, and then waits for the API container to report healthy —
so a red workflow means a genuinely broken deploy, not just a failed SSH.

Then create your first tenant. There are two ways, and the first is easier.

### Sign up in the browser

Set a signup code on the VM and restart the API:

```bash
cd /opt/letthemknow
printf 'LTK_SIGNUP_CODE=%s\n' "$(openssl rand -base64 18)" >> .env
grep '^LTK_SIGNUP_CODE=' .env      # copy the value, you need it on the form
docker compose up -d api
```

Open `https://letthemknow.hkwprince.com/signup`, fill in the company, your email, a password and that
code, and you land signed in as the administrator of a new workspace.

**An empty or missing `LTK_SIGNUP_CODE` disables signup rather than opening it**, so a deployment is
closed until you deliberately open it. Keep the code out of the repository: `scripts/check-secrets.sh`
refuses to commit it. To close signup again, blank the value and restart the API.

A wrong code and a disabled deployment return exactly the same refusal, so nobody can use the page to
work out whether signup exists here or whether a guess was close.

### Or provision from the command line

Useful when nobody can sign in, or when you would rather not open signup at all. **Stop the API first.**
`docker compose run` starts a *second*
complete instance of the application: the provisioning CLI runs on `ApplicationReadyEvent`, so the whole
app boots — Tomcat, JPA, the connection pool and the dispatch workers — before it inserts two rows and
exits. On a 1 GB VM that does not fit beside the running one, and the kernel may pick the live API as
the thing to kill.

```bash
cd /opt/letthemknow
docker compose stop api            # frees the 420 MB cap; the second instance cannot fit beside it
docker compose run --rm -e APP_WORKER_ENABLED=false api --provision-tenant \
  --name=yourcompany --admin-email=you@hkwprince.com --admin-password='a-strong-password'
docker compose start api           # ~6 min to healthy, see the boot-time section above
```

`APP_WORKER_ENABLED=false` matters for a reason that has nothing to do with memory. Left on, the
throwaway instance joins the `dispatchers` consumer group, can claim a chunk of recipients into
`SENDING`, and then exits mid-flight when the CLI calls `System.exit`. `PendingReaper` recovers those
rows after five minutes, so nothing is lost, but those sends stall meanwhile.

The six minutes of downtime is free today, because with no tenants nobody can log in. **Once you have
real tenants, treat this as a maintenance window and never run it while a campaign is dispatching.**

> Do not reach for `--spring.main.web-application-type=none` to make the throwaway instance lighter.
> `SecurityConfig` declares a `SecurityFilterChain` bean that needs `HttpSecurity`, which Spring Security
> only provides in a servlet web context, so the context fails to start. Stopping the live container is
> the cheaper and safer way to get the same headroom.

Open `https://letthemknow.hkwprince.com`, sign in, and configure SMTP under **Channels**. The settings are
only saved if the test email actually arrives, so a green save means email genuinely works.

Verify both hostnames:

```bash
curl -s https://letthemknow.hkwprince.com/api/v1/health
curl -s https://letthemknowapi.hkwprince.com/api/v1/health
```

### What the first three deploys taught us

All three numbers below were measured on the real e2-micro, not estimated. They are here because each
one cost a failed deploy, and because they are the difference between "this VM is broken" and "this VM
is small".

| Deploy | Outcome | Cause |
|---|---|---|
| 1 | failed, `api` stuck in `Created` | MySQL's first start had to initialise its data directory, which took **2m50s**. The health window was 140s, so Compose gave up 8 seconds before MySQL was ready and never started anything that depended on it. |
| 2 | stack ran, workflow red | The API booted in **8m32s** against a 6-minute gate. The container went healthy 40 seconds after the workflow had already declared failure. |
| 3 | green | JVM startup flags cut the boot to **5m58s**. |

The fix for the third was `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k` in `api/Dockerfile`.
C1-only compilation drops the second-tier compiler threads and the serial collector drops the parallel
GC workers, which together are most of what a JVM spends during startup. Peak throughput is irrelevant
at this scale. The same jar boots in 19 seconds on a laptop, so **the 6 minutes is the machine, not the
application**: a quarter of a shared vCPU, with the JVM partly swapped to a standard persistent disk.

Steady state once it is up, which is the part that matters:

| | |
|---|---|
| Memory | 464 MB of 958 MB used, `api` at 143 MB of its 420 MB cap |
| Swap | 443 MB used, no container ever OOM-killed |
| API latency through the tunnel | 0.4–0.6 s |

Two consequences worth planning around. **Restarts are expensive**: budget six minutes of downtime for
any deploy that replaces the API container, and do not deploy during a send window. And **the health
gates are deliberately generous** (180s start period, 12-minute workflow timeout) so a slow-but-alive
start is never reported as a failure. If you ever move to a bigger machine, tighten them again.

## 6a. Publishing this repository

If you make this repository public, the audit below is what was checked first. Anything in git history
becomes public too, and is scraped within minutes, so history matters as much as the current files.

| Checked | Result |
|---|---|
| Vendor tokens, private keys across every commit | clean |
| VM IP address and Linux username in tracked files | absent; they live only in GitHub secrets |
| Demo credentials in `db/seed/V2__seed_dev.sql` | safe to publish: loaded only by the `local` and `test` profiles, because `application-prod.yml` pins Flyway to `classpath:db/migration` |
| `.env` | ignored and never tracked |

**`.gitignore` is not the control that matters here.** It governs files git does not track. It cannot
help when a real value is pasted into a file git tracks by design, which is what this runbook is. That
is the likelier mistake, and it has already happened in this file more than once during setup.

The control for that is `scripts/check-secrets.sh`. Install it as a hook once:

```bash
ln -sf ../../scripts/check-secrets.sh .git/hooks/pre-commit
scripts/check-secrets.sh --self-test    # proves it blocks known-bad content
scripts/check-secrets.sh --history      # run this before flipping the repo to public
```

It refuses any commit containing a vendor token, a private key, or one of this project's secret
variables assigned a value that is not an obvious placeholder. That last rule is the important one: a
base64 key matches no vendor pattern, so prefix-matching alone would miss it.

Also turn on GitHub's own **secret scanning and push protection** under Settings, Code security. Both
are free on public repositories and catch what a local hook cannot, such as a push from another machine.

The standing rule, worth more than either tool: **a real credential never goes in a tracked file.**
Secrets belong in `.env` on the VM, in GitHub Actions secrets, or in a password manager. When a runbook
needs to show one, it shows an empty value or a placeholder.

## 7. When something breaks

### One-command status check

`scripts/vm-status.sh` in the repository checks every step of this runbook and the running stack in one
go: swap, Docker, the GHCR login, the deploy key, every `.env` key (lengths only, never values), container
health and memory against the caps, the API health endpoint from inside its container, whether
`cloudflared` registered a connection, how many Flyway migrations applied, and recent API errors. Each
line is marked `OK`, `FAIL` or `NOTE`, and every `FAIL` names the runbook step that fixes it.

Upload it once through the browser SSH window (gear icon → **Upload file**), then:

```bash
mv ~/vm-status.sh /opt/letthemknow/ && chmod +x /opt/letthemknow/vm-status.sh
bash /opt/letthemknow/vm-status.sh
```

It is read-only and prints no secrets, so its output is safe to paste into a chat or an issue.

### Manual checks

```bash
cd /opt/letthemknow
docker compose ps                     # who is up, who is restarting
docker compose logs --tail=100 api
docker stats --no-stream              # memory against the caps
free -h                               # is swap being eaten
```

| Symptom | Cause | Fix |
|---|---|---|
| A container keeps restarting; `docker inspect -f '{{.State.OOMKilled}}' letthemknow-api-1` is `true` | Out of memory | Confirm swap is on. Do not raise `mem_limit` without lowering another. |
| `denied` or `unauthorized` on pull | The GHCR login expired, was never done, or used a fine-grained token | Redo `docker login ghcr.io` from step 2 with a **classic** token |
| Site shows a Cloudflare 502 | The tunnel is up but the service behind it is not | `docker compose logs cloudflared`, then check `web`/`api` are healthy |
| Tunnel shows **Down** under Networking → Tunnels | `cloudflared` cannot start, usually a bad token | `docker compose logs cloudflared`, re-copy the token into `.env`, `docker compose up -d cloudflared` |
| Deploy workflow fails at the SSH step with `attempted methods [none publickey]` | The guest agent deleted `~/.ssh/authorized_keys`, usually after a console SSH session's temporary key expired | Add the key to **instance metadata**, not `authorized_keys`. See the warning in step 3. Test with `ssh -i ~/.ssh/letthemknow_deploy USER@VM_HOST` |
| Site returns Cloudflare **error 1033** / HTTP 530 | The tunnel has no connection: `cloudflared` is not running, so the origin is unreachable | `docker compose ps`; if containers are down, `docker compose up -d`. DNS is fine, the origin is not |
| Login works but campaigns never send | Workers disabled | `APP_WORKER_ENABLED=true` in `.env`, then `docker compose up -d api` |

### Backups

Everything that matters is in MySQL — tenants, templates, campaigns, and the encrypted channel secrets.

```bash
docker compose exec -T mysql mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction letthemknow | gzip > ~/ltk-$(date +%F).sql.gz
```

A dump is useless without `LTK_MASTER_KEY`, which is what decrypts the stored channel secrets. Keep the
key somewhere separate from the dumps.
