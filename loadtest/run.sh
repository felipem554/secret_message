#!/usr/bin/env bash
# Runs one load-test scenario and samples the app container while it runs.
#
#   ./run.sh <scenario> [extra k6 args]
#   scenario: create-small | create-reveal | create-1mb | idempotent-create
#
# Uses a local `k6` if installed, otherwise the grafana/k6 Docker image
# (host networking, so BASE_URL=http://localhost:8080 works in both cases).
#
# Besides the k6 report it prints, per stage, the app container's CPU % and
# memory (docker stats), and whether the JVM exited during the run (OOM exit
# via -XX:+ExitOnOutOfMemoryError). Everything lands in loadtest/results/.
#
# Env: every variable documented in lib/config.js and README.md, plus
#   APP_CONTAINER  container to sample (default: the compose `app` service)
#   K6_IMAGE       default grafana/k6:latest
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

scenario="${1:-}"
if [[ -z "$scenario" || ! -f "$here/$scenario.js" ]]; then
  echo "usage: $0 <create-small|create-reveal|create-1mb|idempotent-create> [k6 args]" >&2
  exit 2
fi
shift

export GIT_SHA="${GIT_SHA:-$(git -C "$repo" rev-parse --short HEAD 2>/dev/null || echo unknown)}"
if [[ -n "$(git -C "$repo" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
  GIT_SHA="$GIT_SHA-dirty"
fi
results="$here/results"
mkdir -p "$results"
stamp="$(date -u +%Y-%m-%dT%H-%M-%S)"
# One name for every file of this run: <ts>-[<label>-]<scenario>-<profile>
export RUN_NAME="$stamp-${LABEL:+$LABEL-}$scenario-${PROFILE:-steps}"
base="$results/$RUN_NAME"
stats_csv="$base-stats.csv"
env_txt="$base-env.txt"

container="${APP_CONTAINER:-$(docker compose -f "$repo/compose.yaml" ps -q app 2>/dev/null || true)}"

# Snapshot of what is under test, so tuning variants on the same commit can be
# told apart. Env values whose names look secret are redacted.
{
  echo "run:       $RUN_NAME"
  echo "label:     ${LABEL:-}"
  echo "note:      ${NOTE:-}"
  echo "n_procs:   ${N_PROCS:-1}"
  echo "git:       $GIT_SHA"
  git -C "$repo" status --porcelain --untracked-files=no 2>/dev/null | sed 's/^/  modified: /'
  echo "host:      $(nproc) cpus, $(awk '/MemTotal/ {printf "%.1f GiB", $2/1048576}' /proc/meminfo)," \
       "$(lscpu 2>/dev/null | sed -n 's/^Model name: *//p')"
  echo "docker:    $(docker version -f '{{.Server.Version}}' 2>/dev/null || echo -)"
  echo "k6 env:    $(for v in PROFILE STEPS RATES WARMUP HOLD MAX_VUS THINK_TIME MESSAGE_BYTES CLEANUP; do
                       [[ -n "${!v:-}" ]] && printf '%s=%s ' "$v" "${!v}"; done)"
  if [[ -n "$container" ]]; then
    echo
    echo "container: $(docker inspect -f '{{.Name}}' "$container" | tr -d /)"
    echo "image:     $(docker inspect -f '{{.Image}}' "$container")"
    docker inspect -f 'limits:    cpus={{.HostConfig.NanoCpus}} (1e9 = 1 cpu, 0 = none) cpu_quota={{.HostConfig.CpuQuota}} memory={{.HostConfig.Memory}} bytes (0 = none)' "$container"
    echo "jvm cmd:   $(docker exec "$container" cat /proc/1/cmdline 2>/dev/null | tr '\0' ' ' || echo -)"
    echo "env:"
    docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container" | sort |
      sed -E '/^$/d; s/^([^=]*(KEY|PASS|SECRET|TOKEN|CREDENTIAL)[^=]*)=.*/\1=<redacted>/I; s/^/  /'
  fi
} > "$env_txt"

sampler_pid=""
if [[ -n "$container" ]]; then
  restarts_before="$(docker inspect -f '{{.RestartCount}}' "$container")"
  echo "epoch_ms,cpu_pct,mem_mib" > "$stats_csv"
  (
    while docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; do
      line="$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}}' "$container" 2>/dev/null)" || break
      cpu="${line%%,*}"; cpu="${cpu%\%}"
      mem="${line#*,}"; mem="${mem%% /*}"
      # MemUsage looks like "312.4MiB" or "1.02GiB"
      case "$mem" in
        *GiB) mem="$(awk -v m="${mem%GiB}" 'BEGIN{printf "%.1f", m*1024}')" ;;
        *MiB) mem="${mem%MiB}" ;;
        *KiB) mem="$(awk -v m="${mem%KiB}" 'BEGIN{printf "%.1f", m/1024}')" ;;
      esac
      echo "$(date +%s%3N),$cpu,$mem" >> "$stats_csv"
    done
  ) &
  sampler_pid=$!
else
  echo "warning: no app container found (set APP_CONTAINER); skipping CPU/memory sampling" >&2
fi

forward_vars=(BASE_URL PROFILE STEPS RATES WARMUP HOLD MAX_VUS THINK_TIME MESSAGE_BYTES MAX_MESSAGE_SIZE
              MIN_RATE_LIMIT_REMAINING CLEANUP GIT_SHA NOTE LABEL N_PROCS RESULTS_DIR RUN_NAME)

set +e
if command -v k6 >/dev/null 2>&1; then
  (cd "$here" && k6 run "$@" "$scenario.js")
else
  env_args=()
  for v in "${forward_vars[@]}"; do
    [[ -n "${!v:-}" ]] && env_args+=(-e "$v=${!v}")
  done
  docker run --rm -i --network host -u "$(id -u):$(id -g)" \
    -v "$here:/scripts" -w /scripts "${env_args[@]}" \
    "${K6_IMAGE:-grafana/k6:latest}" run "$@" "$scenario.js"
fi
k6_exit=$?
set -e

[[ -n "$sampler_pid" ]] && kill "$sampler_pid" 2>/dev/null && wait "$sampler_pid" 2>/dev/null || true

# Printed and appended to the k6 report, so <run>.txt holds the whole picture.
if [[ -n "$container" ]]; then {
  echo "=== app container during the run ($(docker inspect -f '{{.Name}}' "$container" | tr -d /)) ==="
  summary_json="$base.json"
  if [[ -s "$summary_json" && -s "$stats_csv" ]]; then
    printf '%-10s %10s %10s %12s\n' stage cpu_avg% cpu_max% mem_max_MiB
    jq -r '.stages[] | select(.startMs != null) | "\(.name) \(.startMs) \(.endMs)"' "$summary_json" |
      while read -r name start end; do
        awk -F, -v s="$start" -v e="$end" -v n="$name" '
          NR > 1 && $1 >= s && $1 < e { c += $2; k++; if ($2 > cm) cm = $2; if ($3 > mm) mm = $3 }
          END { if (k) printf "%-10s %10.1f %10.1f %12.1f\n", n, c / k, cm, mm; else printf "%-10s %10s\n", n, "no samples" }
        ' "$stats_csv"
      done
    echo "(CPU % is of one core: 400% = 4 cores busy. Raw samples: ${stats_csv#"$repo/"})"
  fi

  state="$(docker inspect -f 'running={{.State.Running}} exit={{.State.ExitCode}} oomkilled={{.State.OOMKilled}} restarts={{.RestartCount}}' "$container")"
  echo "state after run: $state (restarts before: $restarts_before)"
  if ! docker inspect -f '{{.State.Running}}' "$container" | grep -q true; then
    echo "!!! app JVM exited during the run (exit 3 = -XX:+ExitOnOutOfMemoryError). Record as an OOM exit."
    echo "    last log lines:"; docker logs --tail 5 "$container" 2>&1 | sed 's/^/    /'
  fi
} | tee -a "$base.txt"; fi
echo "results: ${base#"$repo/"}.{txt,json}, -stats.csv, -env.txt"

exit "$k6_exit"
