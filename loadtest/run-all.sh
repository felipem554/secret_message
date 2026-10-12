#!/usr/bin/env bash
# Runs a full comparable set: fresh app JVM and empty Redis, then every
# scenario in turn with the same LABEL. Use it for the baseline and for every
# tuning variant, so all of them go through the same procedure.
#
#   LABEL=BASE ./run-all.sh
#   LABEL=T2-g1gc NOTE="G1, 512m heap" JAVA_OPTS="... -XX:+UseG1GC" ./run-all.sh
#   LABEL=T1-tomcat400 ./run-all.sh create-small create-reveal
#
# Arguments: scenarios to run (default: all four, create-1mb last because it
# may OOM the JVM). Every env var run.sh accepts (PROFILE, STEPS, NOTE, ...) is
# passed through, as is anything compose.yaml reads (JAVA_OPTS, ...).
#
# Env:
#   LABEL                         required; tags files and results-log rows
#   APP_RATELIMIT_REQUESTSPERDAY  default 100000000 (the app default of 100
#                                 would make every pre-flight abort)
#   FLUSH                         default 1; 0 keeps the Redis data
#                                 (1 runs FLUSHALL: deletes every key in the
#                                 local dev Redis)
#   SETTLE                        default 15; seconds to wait after the app is
#                                 healthy, before the next scenario
#   HEALTH_TIMEOUT                default 180; seconds to wait for the app
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"
compose=(docker compose -f "$repo/compose.yaml")

if [[ -z "${LABEL:-}" ]]; then
  echo "usage: LABEL=<run id> $0 [scenario ...]   (e.g. LABEL=BASE)" >&2
  exit 2
fi
export LABEL
scenarios=("$@")
[[ ${#scenarios[@]} -eq 0 ]] && scenarios=(create-small create-reveal idempotent-create create-1mb)
for s in "${scenarios[@]}"; do
  [[ -f "$here/$s.js" ]] || { echo "unknown scenario: $s" >&2; exit 2; }
done

# Exported, not inline: compose reads it again on every restart below, and a
# restart without it brings the app back with the limit of 100.
export APP_RATELIMIT_REQUESTSPERDAY="${APP_RATELIMIT_REQUESTSPERDAY:-100000000}"

wait_healthy() {
  local deadline=$((SECONDS + ${HEALTH_TIMEOUT:-180}))
  until curl -fsS localhost:8080/actuator/health >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "app not healthy after ${HEALTH_TIMEOUT:-180}s; last log lines:" >&2
      "${compose[@]}" logs --tail 20 app >&2
      exit 1
    fi
    sleep 2
  done
}

if [[ -n "$(git -C "$repo" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
  echo "note: uncommitted changes; runs are tagged $(git -C "$repo" rev-parse --short HEAD)-dirty"
fi

# Fresh JVM for every set, so the numbers do not depend on what ran before.
echo "=== starting stack (LABEL=$LABEL) ==="
"${compose[@]}" up -d --build --force-recreate app
wait_healthy
if [[ "${FLUSH:-1}" == 1 ]]; then
  "${compose[@]}" exec -T redis redis-cli -a "${REDIS_PASSWORD:-redispassword}" --no-auth-warning FLUSHALL
fi
sleep "${SETTLE:-15}"

started_at="$(date +%s)"
declare -A status
for s in "${scenarios[@]}"; do
  echo "=== $s ($LABEL) ==="
  set +e
  "$here/run.sh" "$s"
  rc=$?
  set -e
  case "$rc" in
    0)   status[$s]="pass" ;;
    99)  status[$s]="thresholds failed" ;;
    107) status[$s]="setup failed"
         echo "setup failed (app down or rate limit not raised); stopping" >&2
         break ;;
    *)   status[$s]="exit $rc" ;;
  esac
  # Brings the JVM back if the scenario killed it (OOM exit), same env as above.
  "${compose[@]}" up -d app
  wait_healthy
  sleep "${SETTLE:-15}"
done

echo
echo "=== $LABEL summary ==="
for s in "${scenarios[@]}"; do
  printf '%-18s %s\n' "$s" "${status[$s]:-not run}"
done
echo
echo "Results-log rows:"
# Only this invocation's files: an earlier set with the same LABEL is left out.
mapfile -t reports < <(find "$here/results" -maxdepth 1 -name "*-$LABEL-*.txt" ! -name '*-env.txt' \
                         -newermt "@$started_at" | sort)
if [[ ${#reports[@]} -gt 0 ]]; then grep -h "^| $LABEL |" "${reports[@]}"; else echo "(none)"; fi
