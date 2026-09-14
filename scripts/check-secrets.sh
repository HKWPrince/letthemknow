#!/usr/bin/env bash
#
# Refuse to commit credentials that live inside *tracked* files.
#
# .gitignore protects files git does not track, such as .env. It cannot help when a real value is
# pasted into a file git tracks by design: a runbook, a README, a test fixture. That is this leak.
#
#   scripts/check-secrets.sh            scan what is staged (use as the pre-commit hook)
#   scripts/check-secrets.sh --all      scan every tracked file in the working tree
#   scripts/check-secrets.sh --history  scan every commit (run before making the repo public)
#
# Install the hook:
#   ln -sf ../../scripts/check-secrets.sh .git/hooks/pre-commit
#
# Exit 0 clean, 1 if something looks like a live credential.

set -uo pipefail
mode=${1:---staged}
fail=0

report() { printf '  \033[31mBLOCKED\033[0m  %s\n            %s\n' "$1" "$2"; fail=1; }

# A value is a placeholder if it is empty or self-evidently fake. Anything else assigned to one of
# the names below is treated as live. This rule is what catches a pasted base64 key, which matches
# no vendor token shape.
# `^\$\{` and `^\$\(` cover values that are a variable expansion or command substitution: those are
# code that produces a value, never the value itself.
PLACEHOLDER='CHANGE_ME|PASTE|YOUR_|REPLACE|EXAMPLE|SAMPLE|xxxx|XXXX|\.\.\.|^<.*>$|^\$\{|^\$\(|^\*+$|^-+$|^your-'

SECRET_NAMES='LTK_MASTER_KEY|LTK_JWT_SECRET|MYSQL_ROOT_PASSWORD|DB_PASSWORD|CLOUDFLARE_TUNNEL_TOKEN|GHCR_TOKEN|MYSQL_PASSWORD'

# Files whose credentials are fixtures by design, verified as such:
#   .env.example                 the template; its values are the compose defaults, never a real host
#   db/seed/V2__seed_dev.sql     demo tenant; loaded only by the local and test profiles, because
#                                application-prod.yml pins flyway to classpath:db/migration
#   api/src/test/                test fixtures, compiled only into the test classpath
# Narrow on purpose. Widening this list is how a guard stops being one.
ALLOW_PATHS='^\.env\.example$|^api/src/test/|^api/src/main/resources/db/seed/'

# Vendor shapes that are live whenever they appear
HARD='ghp_[A-Za-z0-9]{30,}|gho_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN ([A-Z]+ )?PRIVATE KEY|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{32,}|eyJhbGciOi[A-Za-z0-9._-]{30,}|ltk_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}'

scan_text() {   # $1 = label; content on stdin
  local label=$1 line n=0 val
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n+1))
    # Skip this script's own rule definitions and any line that is telling you not to do it.
    case "$line" in
      *SECRET_NAMES=*|*HARD=*|*PLACEHOLDER=*) continue ;;
      *"never paste"*|*"Never paste"*|*"ever paste a real"*) continue ;;
    esac
    if printf '%s' "$line" | grep -qE "$HARD"; then
      report "$label:$n" "$(printf '%s' "$line" | sed -E 's/[A-Za-z0-9_-]{20,}/<redacted>/g' | cut -c1-90)  [vendor token or private key]"
      continue
    fi
    if printf '%s' "$line" | grep -qE "^[[:space:]]*($SECRET_NAMES)="; then
      val=$(printf '%s' "$line" | sed -E "s/^[[:space:]]*($SECRET_NAMES)=//" | tr -d '"'"'"' \r')
      [ -z "$val" ] && continue
      printf '%s' "$val" | grep -qE "$PLACEHOLDER" && continue
      report "$label:$n" "$(printf '%s' "$line" | sed -E 's/=.*/=/')<redacted, ${#val} chars>  [looks like a real value]"
    fi
  done
}

case "$mode" in
  --staged)
    files=$(git diff --cached --name-only --diff-filter=ACM)
    [ -z "$files" ] && { echo "  nothing staged"; exit 0; }
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      case "$f" in *.png|*.jpg|*.jpeg|*.ico|*.woff*|*.jar|*lock.yaml|*lock.json) continue;; esac
      printf '%s' "$f" | grep -qE "$ALLOW_PATHS" && continue
      # Redirect, never pipe. On the right of a pipe scan_text runs in a subshell, its `fail=1` is
      # discarded, and the hook exits 0 while printing BLOCKED. That bug let a bad commit through.
      scan_text "$f" < <(git show ":$f" 2>/dev/null)
    done <<< "$files"
    ;;
  --all)
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      case "$f" in *.png|*.jpg|*.jpeg|*.ico|*.woff*|*.jar|*lock.yaml|*lock.json) continue;; esac
      printf '%s' "$f" | grep -qE "$ALLOW_PATHS" && continue
      [ -f "$f" ] && scan_text "$f" < "$f"
    done <<< "$(git ls-files)"
    ;;
  --history)
    # Default scans every reachable commit. Pass a ref to scan only what you are about to publish,
    # e.g. `--history main` when an old tip is still held by a local backup tag.
    range=${2:---all}
    for c in $(git rev-list "$range"); do
      while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        rest=${hit#*:}; path=${rest%%:*}
        printf '%s' "$path" | grep -qE "$ALLOW_PATHS" && continue
        report "commit ${c:0:8}" "$(printf '%s' "$rest" | sed -E 's/[A-Za-z0-9_+\/-]{20,}/<redacted>/g' | cut -c1-90)"
      done <<< "$(git grep -I -n -E "$HARD" "$c" -- 2>/dev/null | head -5)"
      while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        rest=${hit#*:}; path=${rest%%:*}
        printf '%s' "$path" | grep -qE "$ALLOW_PATHS" && continue
        # Strip "path:line:" to reach the source line, then test the assigned value on its own.
        # Testing the whole grep output instead would defeat every anchored placeholder rule.
        content=${rest#*:}; content=${content#*:}
        val=$(printf '%s' "$content" | sed -E "s/^[[:space:]]*($SECRET_NAMES)=//" | tr -d '"'"'"' \r')
        [ -z "$val" ] && continue
        printf '%s' "$val" | grep -qE "$PLACEHOLDER" && continue
        report "commit ${c:0:8}" "$(printf '%s' "$rest" | sed -E 's/=.*/=<redacted>/' | cut -c1-90)"
      done <<< "$(git grep -I -n -E "^[[:space:]]*($SECRET_NAMES)=[^[:space:]]" "$c" -- 2>/dev/null | head -5)"
    done
    ;;
  --self-test)
    # Synthetic vectors, shaped like the real mistakes but obviously not real values. A guard whose
    # own test data is a live-looking credential would republish the thing it exists to prevent.
    echo "  feeding known-bad content to the scanner; every line below must be BLOCKED:"
    scan_text "self-test" <<BAD
LTK_MASTER_KEY=$(printf 'A%.0s' $(seq 43))=
LTK_JWT_SECRET=$(printf 'B%.0s' $(seq 43))=
echo 'ghp_$(printf 'C%.0s' $(seq 36))' | docker login ghcr.io
BAD
    echo "  and every line below must be allowed:"
    scan_text "self-test-ok" <<'OK'
LTK_MASTER_KEY=
LTK_MASTER_KEY=CHANGE_ME_run_openssl_rand_-base64_32
GHCR_OWNER=hkwprince
echo 'ghp_PASTE_YOUR_TOKEN_HERE' | docker login ghcr.io
master-key: ${LTK_MASTER_KEY:bGV0dGhlbWtub3ctbG9jYWwtZGV2LW1hc3Rlci1rZXk=}
OK
    if [ "$fail" -eq 1 ]; then echo; echo "  self-test result: 3 blocked above, 0 below = correct"; exit 0
    else echo "  SELF-TEST FAILED: known-bad content was not blocked"; exit 1; fi
    ;;
  *) echo "usage: $0 [--staged|--all|--history|--self-test]"; exit 2;;
esac

if [ "$fail" -eq 0 ]; then
  printf '  \033[32mclean\033[0m  no live-looking credentials found (%s)\n' "$mode"
else
  cat <<'EOT'

  Refused. If that is a real credential:
    1. Replace it with a placeholder in the file.
    2. Rotate it. Treat anything committed as already compromised.
  If it is a false positive, make the placeholder obvious: CHANGE_ME, YOUR_TOKEN, ${VAR}.
EOT
fi
exit $fail
