# Scaling Plan: Multiple Processes Behind nginx

Status: proposed · Scope: HTTP throughput (req/s) and CPU use of the app tier

## 1. Where we are today

### Processes and threads

| Deployment | App processes | Load balancer in front |
|---|---|---|
| `compose.yaml` / `compose.ghcr.yaml` | **1** JVM (port 8080 published directly) | none |
| k8s dev overlay | 1 pod = 1 JVM | ingress-nginx |
| k8s prod overlay | **2** pods = 2 JVMs | ingress-nginx (round-robin across pod endpoints) |

Each JVM is already multi-threaded:

| Pool | Size | Source |
|---|---|---|
| Tomcat request threads | max 200 (Spring Boot default), accept queue 100 | no `server.tomcat.threads.*` set |
| Jedis connection pool (shared by messages, idempotency and Bucket4j) | **max 8**, `maxWait = -1` (block forever) | `RedisConfig`: `usePooling()` with commons-pool2 defaults |
| NATS dispatchers | **1 thread per subject** (`save.msg`, `receive.msg`) | `NatsService.createDispatcher` |
| GC | **SerialGC, 128 MB max heap** | JVM ergonomics under the 512 MB container limit (measured: `docker run --memory 512m eclipse-temurin:21-jre-jammy java -XX:+PrintFlagsFinal`) |

### What this means

**One JVM can already use every core.** Unlike Node.js or CPython, Java has no GIL. Adding JVM processes on the *same* host does not add CPU capacity by itself. Each extra JVM costs ~250–500 MB of RAM and duplicates JIT and GC work. Today's single process is limited by its configuration, not by being a single process:

1. **8 Redis connections for 200 request threads.** Every API request makes 2–4 Redis round-trips (Bucket4j CAS, then `SET`, or `GET`+`DEL`+`DEL`). Under load the 9th concurrent request waits for a connection, with no timeout.
2. **SerialGC + 128 MB heap.** SerialGC pauses all threads on every collection. A 1 MB message is copied roughly 4–5 times (body, `String`, bytes, ciphertext, Base64), so ~25 concurrent max-size creates fill the heap. `-XX:+ExitOnOutOfMemoryError` then kills the process, which makes this a **cheap DoS**.
3. **NATS work is serial.** One dispatcher thread per subject means one internal request at a time per process for each subject.
4. **Shared `SecureRandom`.** Under high concurrency, `NativePRNG` contention is a likely hot spot. Verify with a profiler before changing anything.

Multiple processes behind nginx *are* still worth having, for these reasons:

- availability (one crash or GC pause doesn't stop service)
- zero-downtime rolling deploys
- smaller heaps with shorter pauses
- scaling past one machine

The plan is therefore: **measure → fix the single process → run N processes behind nginx → scale out across hosts.**

## 2. Phase 0: measure first (½ day)

- Load-test tool: k6, or `wrk`/`oha` with a Lua or JSON body. Scenarios:
  - (a) create with small messages
  - (b) create + reveal pairs
  - (c) 1 MB creates
  - (d) mixed traffic with `Idempotency-Key`
- Raise the limiter for the test: `APP_RATELIMIT_REQUESTSPERDAY=100000000`. Otherwise one test IP gets 429 after 100 requests.
- Watch these: p50/p99 latency, req/s, error rate, CPU per process, GC pauses (`-Xlog:gc`), and `jedis` pool wait (add `management.endpoints.web.exposure.include=health,metrics,prometheus` internally, or log `JedisPool.getNumWaiters()`).
- Record the baseline in this document before changing anything.

## 3. Phase 1: make one process efficient (1–2 days)

| Change | How | Why |
|---|---|---|
| JVM heap and GC | `JAVA_OPTS += -XX:+UseG1GC -XX:MaxRAMPercentage=70` (or `-XX:+UseZGC -XX:+ZGenerational` for low pause) | ~360 MB heap instead of 128 MB; no stop-the-world SerialGC |
| Jedis pool | `JedisPoolConfig`: `maxTotal≈32–64`, `maxIdle=maxTotal`, `minIdle=8`, `maxWait=500ms` | Pool stops being the bottleneck; fail fast instead of hanging |
| Bound concurrency to fit memory | `server.tomcat.threads.max` sized to heap ÷ worst-case request (e.g. 64), plus `limit_conn` in nginx (Phase 2) | Prevents the 1 MB × N OOM kill |
| NATS parallelism | Create *K* dispatchers per subject in the same queue group (a loop in `startNatsSubscriptions`) | NATS spreads queue-group messages across subscriptions in one connection |
| Graceful shutdown | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s` | Needed for drain-on-deploy behind nginx |
| Virtual threads (optional, later) | `spring.threads.virtual.enabled=true` | Only after the pool sizes are right; on Java 21, `synchronized` inside Jedis/commons-pool can pin carriers. Benchmark it before keeping it |

Exit criterion: one process saturates its CPU allotment before it saturates the Redis pool, heap or threads.

## 4. Phase 2: N processes behind nginx on one host (Docker Compose)

### Topology

```
                 ┌────────────── host ───────────────┐
client ─HTTPS──▶ │ nginx :443 ── least_conn ──┬─▶ app#1 :8080 (JVM)
                 │  (TLS, body limit,         ├─▶ app#2 :8080 (JVM)
                 │   limit_conn, XFF)         ├─▶ app#3 :8080 (JVM)
                 │                            └─▶ app#N :8080 (JVM)
                 │        all apps ──▶ redis (shared state) ◀── rate limit,
                 │        all apps ──▶ nats  (queue group)       idempotency
                 └────────────────────────────────────┘
```

The app is already safe to replicate. Rate-limit buckets, idempotency records, messages and attempt counters all live in Redis and use atomic operations (ADR-0001), and NATS uses a queue group. **No sticky sessions are needed.**

### Sizing N

- CPU: `N ≈ cores_for_app / cores_per_process`. Start with 2 cores per JVM (`-XX:ActiveProcessorCount=2`, compose `cpus: "2"`). On this 12-core dev box that gives N≈4–5, leaving room for nginx and Redis.
- Memory: `N × mem_limit` (512 MB each) must fit with headroom.
- Set `ActiveProcessorCount` explicitly. Without it, every JVM sizes its GC and ForkJoin pools for *all* host cores and they fight each other.

### compose.yaml changes

```yaml
services:
  nginx:
    image: nginx:1.27-alpine        # ≥ 1.27.3 for `resolve` in upstream (OSS)
    ports: ["80:80"]                # add 443 + certs for real TLS
    volumes:
      - ./deploy/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on: [app]
    networks: [nats]

  app:
    # remove `ports: - "8080:8080"`: only nginx is reachable from outside
    deploy:
      replicas: 4
      resources:
        limits: { cpus: "2", memory: 512m }
    environment:
      JAVA_OPTS: >-
        -XX:ActiveProcessorCount=2 -XX:+UseG1GC -XX:MaxRAMPercentage=70
        -XX:+DisableAttachMechanism -XX:+ExitOnOutOfMemoryError
        -XX:-HeapDumpOnOutOfMemoryError -Dcom.sun.management.jmxremote=false
```

Scale at runtime with `docker compose up -d --scale app=6`.

### deploy/nginx/nginx.conf (core)

```nginx
worker_processes auto;                 # nginx itself: one worker per core
events { worker_connections 4096; }

http {
  resolver 127.0.0.11 valid=10s ipv6=off;   # Docker DNS: picks up scaled replicas

  upstream secret_message {
    zone secret_message 64k;
    least_conn;                        # requests vary (1 MB creates vs tiny reveals)
    server app:8080 resolve max_fails=3 fail_timeout=10s;
    keepalive 64;                      # reuse upstream connections
  }

  limit_conn_zone $binary_remote_addr zone=perip:10m;
  limit_req_zone  $binary_remote_addr zone=api:10m rate=20r/s;  # edge burst guard; daily quota stays in the app

  server {
    listen 80;
    client_max_body_size 2m;           # 1 MB message + JSON escaping; nginx default 1m would 413
    client_body_buffer_size 2m;        # keep secret bodies in memory, not temp files

    location /api/ {
      limit_conn perip 10;
      limit_req zone=api burst=40 nodelay;

      proxy_pass http://secret_message;
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_set_header Host $host;
      proxy_set_header X-Forwarded-For $remote_addr;   # overwrite, never append client-supplied XFF
      proxy_set_header X-Forwarded-Proto $scheme;

      proxy_connect_timeout 2s;
      proxy_read_timeout 15s;
      proxy_next_upstream error timeout;   # POST is only retried if it never reached an upstream
      proxy_next_upstream_tries 2;
      proxy_request_buffering on;
      proxy_buffering off;                 # don't spool decrypted plaintext to disk
    }

    location = /actuator/health { proxy_pass http://secret_message; }
    location /actuator/ { return 404; }    # metrics stay internal
    access_log /var/log/nginx/access.log combined;  # no bodies logged, ever
  }
}
```

### Correctness rules for the load balancer

- **Never retry reveal after it reached an app.** Reveal is consume-once. If nginx retried after app#1 had already deleted the message, the client would get 404 and the secret would be lost. The default `proxy_next_upstream` (without `non_idempotent`) already guarantees this. Do not add `non_idempotent`.
- Create retries are covered by `Idempotency-Key` on the client side, not by nginx.
- **XFF trust.** nginx overwrites `X-Forwarded-For` with `$remote_addr`, and Tomcat trusts it from RFC1918 sources (`server.tomcat.remoteip.internal-proxies`), which matches the compose network. App containers must not publish ports, otherwise clients could bypass nginx and spoof XFF. `ClientIpFilter` rejects unresolvable client IPs when `APP_ENV=production`.
- **Secret hygiene.** No body logging, no `proxy_cache`, no buffering responses to disk (see `docs/MEMORY_HARDENING.md`).

## 5. Phase 3: Kubernetes (production)

ingress-nginx already load-balances across pods, so this phase is mostly configuration:

| Item | Change |
|---|---|
| Body size | Annotation `nginx.ingress.kubernetes.io/proxy-body-size: "2m"`. **Today's prod rejects max-size messages with 413** (controller default is 1m) |
| CPU | `requests.cpu: 1`, `limits.cpu: 2` (or no limit with `-XX:ActiveProcessorCount`); requests drive scheduling and HPA |
| Autoscaling | `HorizontalPodAutoscaler` on CPU (target ~65%), `minReplicas: 2`, `maxReplicas` bounded by Redis connections (`replicas × maxTotal`) |
| Availability | `PodDisruptionBudget` (`minAvailable: 1`), `topologySpreadConstraints` across nodes |
| Drain | Graceful shutdown (Phase 1) plus a `preStop` sleep of 5–10s so ingress removes the endpoint before Tomcat stops |
| Balancing | Controller ConfigMap `load-balance: ewma` (latency-aware), `upstream-keepalive-connections` |
| Controller lifecycle | The ingress-nginx project was announced for retirement (maintenance ended March 2026). Plan a move to a maintained controller (e.g. NGINX Inc.'s `nginx-ingress`, or Gateway API via Envoy Gateway). The nginx rules above carry over |

## 6. Phase 4: limits behind the app tier

- **Redis** is single-threaded and runs ~2–4 ops per request. Expect it to be the next ceiling (well above 10k ops/s on one core). Watch `INFO commandstats` and latency. Next steps if needed: Bucket4j's Lua-based strategy to cut round-trips, Redis 7 I/O threads, then Redis Cluster (keys are already per-message, so they shard cleanly).
- **Connections:** `total Redis clients = replicas × maxTotal`. Keep it below `maxclients` (10000 by default).
- **NATS:** queue groups already scale linearly with replicas.

## 7. Rollout and success criteria

1. Phase 0 baseline recorded.
2. Phase 1 merged: same load gives lower p99, no Jedis waiters, no GC pauses over 50 ms, and 1 MB flood tests produce no OOM kills.
3. Phase 2: throughput grows roughly linearly from N=1→4 until Redis or host CPU saturates. Killing one app container during a load test causes **zero** failed reveals and only connection-level retries.
4. Phase 3: HPA scales out under load and back in; rolling deploys under load produce no 5xx.
