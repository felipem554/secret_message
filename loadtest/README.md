# Load tests (k6)

Harness for SCALE-1 / SCALE-2 in the scaling plan (`docs/SCALING_PLAN.md`, Phase 0).
Every run reports **throughput and p50 / p90 / p95 / p99 latency per load stage**,
so one run of a scenario fills several rows of the results log.

| Script | What it measures | Requests per iteration |
|---|---|---|
| `create-small.js` | `POST /api/v1/messages`, 200 B message | 1 |
| `create-reveal.js` | create, then reveal with the returned key. Checks that the plaintext round-trips and counts `lost_reveals` (404 after a successful create) | 2 |
| `create-1mb.js` | max-size create (1 MiB − 14 B JSON wrapper, the largest body the API accepts). Each message is revealed afterwards as untimed cleanup, so Redis does not fill up | 1 timed + 1 cleanup |
| `idempotent-create.js` | same `Idempotency-Key` + body sent 3×: 201, then two 200 replays with `duplicate:true` and the same id and key | 3 |

## How a run is staged

Each profile is a list of load levels. Every level gets a **warm-up** window
(ramp up, then let JIT and the pools settle) and a **hold** window. Only hold
windows are measured. Requests are tagged `stage:<name>`, and warm-up traffic is
tagged `phase:warmup` and kept out of every reported number.

```
VUs
200 |                                   ____________
    |                                  /   hold     |
 50 |                 ____________    /             |
    |                /    hold    |__/              |
 10 |   ____________/                               |
    |  /    hold                                     \
  0 |_/                                               \_
     warm  vus-0010   warm  vus-0050  warm  vus-0200  ramp-down
```

| `PROFILE` | Executor | Use it for |
|---|---|---|
| `steps` (default) | `ramping-vus`, closed model: N VUs loop with no think time | Capacity at fixed concurrency. Defaults `STEPS=10,50,200` are the ticket's VU levels |
| `breakpoint` | `ramping-arrival-rate`, open model: k6 starts iterations at a target rate | **Finding max throughput.** When the achieved iter/s falls below the target, or `dropped_iterations` > 0, that level is past saturation |
| `smoke` | 1 VU, 10 s | Checking that the scripts and the stack work |

| Env var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | App (or nginx) URL |
| `STEPS` | `10,50,200` | VU levels (`steps`) |
| `RATES` | `50,100,200,400,800,1600` | Iterations/s levels (`breakpoint`). For `create-reveal` one iteration is 2 requests; for `idempotent-create` it is 3 |
| `WARMUP` / `HOLD` | `30s` / `2m` | Per level |
| `MAX_VUS` | `1000` | VU cap for `breakpoint` |
| `THINK_TIME` | `0` | Seconds of sleep per iteration |
| `MESSAGE_BYTES` | 200 (1 MiB − 14 for `create-1mb`) | Message size |
| `CLEANUP` | `1` | `create-1mb` only: `0` skips the cleanup reveal. Redis then grows about 1.4 GB per 1,000 messages |
| `N_PROCS` | `1` | Written into the results-log rows (Phase 2 runs) |
| `LABEL` | — | Run ID, e.g. `BASE`, `T1-tomcat400`. Goes into the file names, the report header and the Run ID column of the results-log rows. Use a distinct label for every tuning variant |
| `NOTE` | — | Free text printed in the report header |

## Running

### 1. Start the stack with the rate limit raised

The default limit is 100 requests per IP per day. One load generator would get
429 after 100 requests, so raise it for the test run only:

```bash
APP_RATELIMIT_REQUESTSPERDAY=100000000 docker compose up -d --build
curl -s localhost:8080/actuator/health
```

Leaving the variable unset keeps the default of 100. Check it with
`curl -si -XPOST localhost:8080/api/v1/messages -H 'Content-Type: application/json' -d '{"message":"x"}' | grep -i x-ratelimit-remaining`.

Every script runs a pre-flight check first. It aborts if the app is down or if
`X-RateLimit-Remaining` is below 1,000,000, and a 429 during the run also
aborts it. You never get a report that is really a rate-limiter benchmark.

### 2. Run a scenario

```bash
cd loadtest
./run.sh create-small                                   # 10 → 50 → 200 VUs
./run.sh create-reveal
STEPS=10,50,100,200,400 ./run.sh create-reveal          # extra levels
PROFILE=breakpoint RATES=200,400,800,1600,3200 ./run.sh create-small
PROFILE=smoke ./run.sh idempotent-create
```

`run.sh` uses a local `k6` if one is installed, otherwise the `grafana/k6`
Docker image with host networking. While k6 runs, it samples the app
container with `docker stats`, then prints CPU % and memory **per stage**. It also
reports whether the JVM exited during the run (`-XX:+ExitOnOutOfMemoryError`
exits with code 3). Exit codes: 0 pass, 99 thresholds failed, 107 setup
failed.

Thresholds that fail the run, all measured windows only:
`http_req_failed < 0.1%`, `checks > 99.9%`, zero 429s, and zero lost reveals in `create-reveal`.

### 3. Read the report

```
Per stage (all endpoints):
   stage  VUs  requests  req/s  iter/s  p50  p90  p95   p99   max  err%
vus-0010   10     ...
Per stage and endpoint:            (create-reveal, idempotent-create)
   stage  endpoint  requests  req/s  p50  p90  p95  p99  max
...
Results-log rows (docs/SCALING_TICKETS.md; fill Run ID, CPU, Jedis, GC, OOM, notes):
| | 2026-10-06 | | a9e32b5 | create-small | 10 | 1 | 812.3 | 1.1 | 3.0 | 4.2 | 0.00 | | | | | p90=2.4 |
=== app container during the run ===
stage        cpu_avg%   cpu_max%  mem_max_MiB
```

- **Max throughput at a concurrency** is the `req/s` of the stage. The knee is
  where `req/s` stops growing between stages while p99 keeps rising.
- CPU % is per core, so 400% means 4 cores busy. If CPU is well below the
  container's cores while p99 grows, the limit is somewhere else (the Jedis pool
  of 8, SerialGC pauses, Tomcat threads). That is exactly what Phase 1 targets.
- Files in `loadtest/results/` (gitignored). All four share one run name,
  `<ts>-[<LABEL>-]<scenario>-<profile>`:
  - `.json`: full k6 summary and per-stage numbers with absolute time windows
  - `.txt`: the full report, including the container CPU/memory table and the JVM exit state
  - `-stats.csv`: raw `docker stats` samples
  - `-env.txt`: what was under test: git SHA (`-dirty` plus the modified files when the
    tree has uncommitted changes), host CPUs/RAM, the container's image, CPU and memory
    limits, actual JVM command line and env vars (secret-looking values redacted)

## Recording the baseline (SCALE-2)

Run every scenario once with the default `steps` profile against unmodified
`main`. That gives 4 × 3 = 12 `BASE` rows, in about 35 min:

```bash
cd loadtest
LABEL=BASE ./run-all.sh
```

`run-all.sh` is the same procedure for the baseline and for every tuning
variant. It rebuilds and recreates the app container (a fresh JVM), runs
`FLUSHALL` on the local dev Redis (**deletes every key**; `FLUSH=0` skips it),
exports the raised rate limit so restarts keep it, runs the scenarios with
`create-1mb` last, and restarts the JVM and waits for health after each one.
It stops if a pre-flight fails, and ends with a pass/fail line per scenario
and the results-log rows of that invocation.

Tuning variants get their own `LABEL`; anything `compose.yaml` reads is passed
through to the container:

```bash
HARDENING="-XX:+DisableAttachMechanism -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError -Dcom.sun.management.jmxremote=false"
LABEL=T1-tomcat400 JAVA_OPTS="$HARDENING -Dserver.tomcat.threads.max=400" ./run-all.sh
LABEL=T2-g1gc      JAVA_OPTS="$HARDENING -XX:+UseG1GC" ./run-all.sh
LABEL=T1-tomcat400 JAVA_OPTS="$HARDENING -Dserver.tomcat.threads.max=400" ./run-all.sh create-small   # a subset
```

Setting `JAVA_OPTS` replaces the compose default, so repeat the hardening
flags as above. Check `-env.txt` afterwards: its `jvm cmd` line is what
actually ran.

Commit before recording. With uncommitted changes the runs are tagged
`<sha>-dirty`; the `-env.txt` files still list what was modified.

Run `create-1mb` last. On the baseline image the JVM is expected to hit OOM and
exit at the higher levels (128 MB SerialGC heap). Record that as a result (OOM
exits = 1, bottleneck = heap). It is the regression SCALE-7 must fix. Stages
after the crash show `conn-error` responses.

Data accumulates in Redis for `auto-delete-days` (2 days). Between baseline
sessions, reset the local dev Redis so runs start from the same state.
**This deletes every key in that Redis:**

```bash
docker compose exec redis redis-cli -a redispassword --no-auth-warning FLUSHALL
```

The Jedis waiters and GC pause columns need SCALE-3 (pool gauges and
`-Xlog:gc`). Leave them empty for the baseline if SCALE-3 has not landed yet.

## Caveats

- k6 and the app share the host when both run locally. k6 uses CPU too, so
  compare runs made on the same machine only, and write the core count down.
- `create-1mb` at 200 VUs keeps about 200 copies of the body in k6
  (~0.5 GB of load-generator RAM).
- The cleanup reveal in `create-1mb` costs app CPU (decrypt plus 1 MiB
  response), but it is not in any reported number.
