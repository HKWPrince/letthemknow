#!/usr/bin/env bash
# LetThemKnow VM status check.
#
# Read-only: changes nothing, prints no secret values (lengths only). Safe to run at any time.
# Keep it at /opt/letthemknow/vm-status.sh on the VM and run:  bash /opt/letthemknow/vm-status.sh
#
# Every line is prefixed so the output reads top to bottom:
#    OK   the runbook step is done
#   FAIL  the step is missing or wrong; the message says which
#   NOTE  information, not a problem

APP_DIR=${APP_DIR:-/opt/letthemknow}
DEPLOY_KEY_FP="SHA256:FnsotlvGdEvFc2bTBKgxhDAGLZdPCXVc4edlaIPWjlI"   # the Actions deploy key
META=http://169.254.169.254/computeMetadata/v1
MH='Metadata-Flavor: Google'

ok()   { printf '   OK   %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; }
note() { printf '  NOTE  %s\n' "$1"; }
head_() { printf '\n=== %s ===\n' "$1"; }
meta() { curl -sf -m 3 -H "$MH" "$META/$1" 2>/dev/null || echo unset; }

# ---------------------------------------------------------------- identity
head_ "Identity  (values for the GitHub secrets)"
echo "  VM_USER  = $(whoami)"
echo "  VM_HOST  = $(meta instance/network-interfaces/0/access-configs/0/external-ip)"
echo "  machine  = $(meta instance/machine-type | awk -F/ '{print $NF}')"
echo "  zone     = $(meta instance/zone | awk -F/ '{print $NF}')"
echo "  os       = $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}")"
echo "  oslogin  = instance:$(meta instance/attributes/enable-oslogin) project:$(meta project/attributes/enable-oslogin)"
echo "  uptime   =$(uptime -p 2>/dev/null | sed 's/^up//')"

# ---------------------------------------------------------------- memory / disk
head_ "Memory and disk"
free -m | awk '/Mem:|Swap:/{printf "  %-5s total=%sMB used=%sMB free=%sMB\n",$1,$2,$3,$4}'
SW=$(free -m | awk '/Swap:/{print $2}')
if [ "${SW:-0}" -ge 1900 ]; then ok "swap ${SW} MB"; else fail "swap ${SW:-0} MB, runbook step 2 requires 2 GB"; fi
if grep -q '/swapfile' /etc/fstab 2>/dev/null; then ok "swap in /etc/fstab, survives reboot"; else fail "swap not in /etc/fstab, gone after reboot"; fi
df -h / | awk 'NR==2{printf "  disk  %s used of %s (%s)\n",$3,$2,$5}'
DU=$(df / | awk 'NR==2{gsub("%","",$5); print $5}')
[ "${DU:-0}" -lt 85 ] && ok "disk ${DU}% used" || fail "disk ${DU}% used, prune images: docker image prune -af"

# ---------------------------------------------------------------- docker
head_ "Docker"
if command -v docker >/dev/null 2>&1; then
  ok "docker $(docker --version | awk '{print $3}' | tr -d ,)"
  if docker compose version >/dev/null 2>&1; then ok "compose plugin $(docker compose version --short 2>/dev/null)"; else fail "docker compose plugin missing"; fi
  if docker info >/dev/null 2>&1; then ok "$(whoami) can use the daemon"; else fail "daemon permission denied: sudo usermod -aG docker \$USER, then log out and in"; fi
else
  fail "docker not installed, runbook step 2"
fi

# ---------------------------------------------------------------- ghcr
head_ "GHCR login"
if [ -f ~/.docker/config.json ] && grep -q '"ghcr.io"' ~/.docker/config.json 2>/dev/null; then
  ok "credential stored for ghcr.io"
else
  fail "no ghcr.io credential, private image pulls will be denied: runbook step 2, classic token"
fi

# ---------------------------------------------------------------- deploy key
head_ "GitHub Actions deploy key"
if [ -f ~/.ssh/authorized_keys ] && ssh-keygen -lf ~/.ssh/authorized_keys 2>/dev/null | grep -q "$DEPLOY_KEY_FP"; then
  ok "deploy key authorised for $(whoami)"
  # A key the guest agent manages is one it will also restore. A hand-appended key is one it deletes,
  # so report where this key actually came from rather than just that it is present today.
  # Match the deploy key by fingerprint, then look for that exact blob in metadata. Taking the first
  # line of authorized_keys instead would read the agent's "# Added by Google" comment and never match.
  blob=$(grep -E '^(ssh-|ecdsa-)' ~/.ssh/authorized_keys 2>/dev/null | while IFS= read -r k; do
           [ "$(printf '%s\n' "$k" | ssh-keygen -lf - 2>/dev/null | awk '{print $2}')" = "$DEPLOY_KEY_FP" ] \
             && printf '%s' "$k" | awk '{print $2}'
         done | head -1)
  if [ -n "$blob" ] && { curl -sf -m 3 -H "$MH" "$META/instance/attributes/ssh-keys" 2>/dev/null;
                         curl -sf -m 3 -H "$MH" "$META/project/attributes/ssh-keys" 2>/dev/null; } \
                       | grep -qF "$blob"; then
    ok "and it is backed by instance/project metadata, so the guest agent will keep it"
  else
    fail "but it is NOT in metadata: the guest agent can delete this file (it already did once). Add the key under Compute Engine -> VM -> Edit -> SSH Keys (runbook step 3)"
  fi
else
  fail "deploy key NOT in ~/.ssh/authorized_keys for $(whoami): the Deploy workflow cannot log in (runbook step 3)"
fi
AK=$(stat -c '%a' ~/.ssh/authorized_keys 2>/dev/null)
[ -n "$AK" ] && { [ "$AK" = 600 ] && ok "authorized_keys mode 600" || fail "authorized_keys mode $AK, sshd wants 600"; }

# ---------------------------------------------------------------- app files
head_ "App directory $APP_DIR"
if [ -d "$APP_DIR" ]; then
  ok "exists, owner $(stat -c '%U' "$APP_DIR")"
  [ -w "$APP_DIR" ] && ok "writable by $(whoami)" || fail "not writable by $(whoami)"
  [ -f "$APP_DIR/docker-compose.yml" ] && ok "docker-compose.yml present" || fail "docker-compose.yml missing: upload it (runbook step 2)"
  if [ -f "$APP_DIR/.env" ]; then
    EM=$(stat -c '%a' "$APP_DIR/.env")
    [ "$EM" = 600 ] && ok ".env present, mode 600" || fail ".env mode is $EM, run: chmod 600 $APP_DIR/.env"
  else
    fail ".env missing (runbook step 5)"
  fi
else
  fail "$APP_DIR does not exist (runbook step 2)"
fi

# ---------------------------------------------------------------- .env contents (lengths only)
head_ ".env keys  (lengths only, values never printed)"
ENVF="$APP_DIR/.env"
envval() { grep -E "^$1=" "$ENVF" 2>/dev/null | head -1 | cut -d= -f2-; }
if [ -f "$ENVF" ]; then
  for k in LTK_MASTER_KEY LTK_JWT_SECRET MYSQL_ROOT_PASSWORD DB_PASSWORD CLOUDFLARE_TUNNEL_TOKEN DB_URL DB_USER REDIS_URL; do
    v=$(envval "$k")
    if [ -n "$v" ]; then ok "$k set (${#v} chars)"; else fail "$k is empty"; fi
  done
  for k in COMPOSE_PROFILES GHCR_OWNER IMAGE_TAG APP_WORKER_ENABLED; do
    v=$(envval "$k")
    if [ -n "$v" ]; then note "$k=$v"; else fail "$k is empty"; fi
  done
  mk=$(envval LTK_MASTER_KEY)
  if [ -n "$mk" ]; then
    b=$(printf '%s' "$mk" | base64 -d 2>/dev/null | wc -c)
    [ "$b" = 32 ] && ok "LTK_MASTER_KEY decodes to 32 bytes" || fail "LTK_MASTER_KEY decodes to $b bytes, API needs exactly 32 and will not start"
  fi
  go=$(envval GHCR_OWNER)
  if [ -n "$go" ]; then
    [ "$go" = "$(printf '%s' "$go" | tr 'A-Z' 'a-z')" ] && ok "GHCR_OWNER is lower-case" \
      || fail "GHCR_OWNER=$go has capitals; images are at ghcr.io/$(printf '%s' "$go" | tr 'A-Z' 'a-z')/... and pulls will fail"
  fi
  [ "$(envval COMPOSE_PROFILES)" = "app" ] || fail "COMPOSE_PROFILES must be 'app' or api/web/cloudflared never start"
fi

# ---------------------------------------------------------------- .env actually reaching the api
head_ "Does the api container receive what .env declares?"
# A value in .env is only interpolated into docker-compose.yml. It reaches the container solely because
# the service lists it under environment:. Setting one without the other looks correct on the VM and is
# silently ignored by the app, which is how LTK_SIGNUP_CODE was set and still refused every code.
if docker inspect letthemknow-api-1 >/dev/null 2>&1 && [ -f "$ENVF" ]; then
  for k in LTK_MASTER_KEY LTK_JWT_SECRET LTK_SIGNUP_CODE APP_WORKER_ENABLED DB_URL DB_USER DB_PASSWORD REDIS_URL; do
    inenv=$(envval "$k")
    [ -n "$inenv" ] || continue
    if docker exec letthemknow-api-1 printenv "$k" >/dev/null 2>&1; then
      ok "$k reaches the container"
    else
      fail "$k is set in .env but NOT passed to the container: add it under the api service's environment: in docker-compose.yml"
    fi
  done
else
  note "api container not present"
fi

# ---------------------------------------------------------------- containers
head_ "Containers"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  if [ "$(docker ps -aq --filter 'label=com.docker.compose.project=letthemknow' | wc -l)" -eq 0 ]; then
    note "no letthemknow containers exist yet; expected before the first successful deploy"
  else
    docker ps -a --filter 'label=com.docker.compose.project=letthemknow' \
      --format '{{.Names}}\t{{.Status}}' 2>/dev/null | sed 's/^/  /'
    echo
    echo "  memory now / cap:"
    docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}' \
      $(docker ps -q --filter 'label=com.docker.compose.project=letthemknow') 2>/dev/null | sed 's/^/  /'
    echo
    for c in $(docker ps -aq --filter 'label=com.docker.compose.project=letthemknow'); do
      n=$(docker inspect -f '{{.Name}}' "$c" | sed 's#^/##')
      oom=$(docker inspect -f '{{.State.OOMKilled}}' "$c")
      rc=$(docker inspect -f '{{.RestartCount}}' "$c")
      img=$(docker inspect -f '{{.Config.Image}}' "$c")
      hs=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$c")
      [ "$oom" = true ] && fail "$n was OOM-killed: check swap, do not raise mem_limit without lowering another"
      [ "${rc:-0}" -gt 0 ] && note "$n restarted $rc time(s)"
      case "$hs" in
        healthy|no-healthcheck) ok "$n $hs  ($img)";;
        *) fail "$n is $hs  ($img)";;
      esac
    done
  fi
fi

# ---------------------------------------------------------------- api health (no host port is published)
head_ "API health  (from inside the container)"
if docker inspect letthemknow-api-1 >/dev/null 2>&1; then
  H=$(docker exec letthemknow-api-1 wget -qO- -T 5 http://localhost:8080/api/v1/health 2>/dev/null)
  if printf '%s' "$H" | grep -q '"UP"'; then ok "$H"; else fail "health did not answer UP: ${H:-no response}"; fi
else
  note "api container not present"
fi

# ---------------------------------------------------------------- tunnel
head_ "Cloudflare tunnel"
if docker inspect letthemknow-cloudflared-1 >/dev/null 2>&1; then
  if docker logs --tail 200 letthemknow-cloudflared-1 2>&1 | grep -q 'Registered tunnel connection'; then
    ok "cloudflared registered a tunnel connection"
  else
    fail "no 'Registered tunnel connection' in the last 200 log lines"
  fi
  echo "  last 5 log lines:"
  docker logs --tail 5 letthemknow-cloudflared-1 2>&1 | cut -c1-160 | sed 's/^/    /'
else
  note "cloudflared container not present"
fi

# ---------------------------------------------------------------- database
head_ "Database"
if docker inspect letthemknow-mysql-1 >/dev/null 2>&1 && [ -f "$ENVF" ]; then
  RP=$(envval MYSQL_ROOT_PASSWORD)
  rows=$(docker exec -e MYSQL_PWD="$RP" letthemknow-mysql-1 \
    mysql -N -u root -e 'SELECT COUNT(*) FROM letthemknow.flyway_schema_history WHERE success=1' 2>/dev/null)
  if [ -n "$rows" ]; then ok "$rows Flyway migration(s) applied"; else fail "could not query flyway_schema_history (mysql down, wrong root password, or API never migrated)"; fi
  tn=$(docker exec -e MYSQL_PWD="$RP" letthemknow-mysql-1 \
    mysql -N -u root -e 'SELECT COUNT(*) FROM letthemknow.tenants' 2>/dev/null)
  [ -n "$tn" ] && note "$tn tenant(s) provisioned"
else
  note "mysql container not present"
fi

# ---------------------------------------------------------------- recent errors
head_ "Recent API errors  (last 200 log lines)"
if docker inspect letthemknow-api-1 >/dev/null 2>&1; then
  ERR=$(docker logs --tail 200 letthemknow-api-1 2>&1 | grep -E ' ERROR |Exception' | tail -n 20)
  if [ -n "$ERR" ]; then note "$(printf '%s' "$ERR" | wc -l | tr -d ' ') error line(s), newest last:"; printf '%s\n' "$ERR" | cut -c1-200 | sed 's/^/    /'; else ok "no ERROR lines"; fi
else
  note "api container not present"
fi

# ---------------------------------------------------------------- outbound
head_ "Outbound reachability"
for t in ghcr.io api.github.com cloudflare.com; do
  code=$(curl -s -o /dev/null -m 8 -w '%{http_code}' "https://$t" 2>/dev/null)
  case "$code" in 2*|3*) ok "$t ($code)";; *) fail "$t unreachable (${code:-timeout})";; esac
done
echo
