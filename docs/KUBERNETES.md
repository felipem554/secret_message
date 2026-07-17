# Kubernetes Deployment Guide

Status: manifests provided and offline-validated; not yet exercised against a
live cluster.

This document explains how to deploy the secret-message stack (Spring Boot
app + Redis + NATS) to Kubernetes using the manifests in [`k8s/`](../k8s/),
and records the design decisions behind them. Docker Compose remains the
local-development path; nothing in `compose.yaml` changed.

## Layout

```
k8s/
├── base/                        # namespace-agnostic resources
│   ├── app/                     # Deployment, Service, ConfigMap
│   ├── redis/                   # Deployment (ephemeral), Service
│   ├── nats/                    # Deployment (core NATS), Service
│   └── network-policies.yaml    # default-deny + explicit allows
└── overlays/
    ├── dev/                     # namespace secret-message-dev, 1 replica
    └── prod/                    # namespace secret-message, 2 replicas,
                                 # Ingress, APP_ENV=production
```

## Prerequisites

- `kubectl` 1.27+ (bundled Kustomize is used via `kubectl apply -k`)
- A cluster whose CNI enforces `NetworkPolicy` (Calico, Cilium, …).
  kind/minikube default CNIs do **not** enforce policies — the manifests
  still apply, but the isolation guarantees silently vanish.
- For the prod overlay: an ingress controller (manifests assume
  `ingressClassName: nginx` in namespace `ingress-nginx` — adjust both in
  `k8s/overlays/prod/ingress.yaml` and `k8s/base/network-policies.yaml`)
  and a TLS certificate in secret `secret-message-tls`.

## Secrets

Each overlay generates the `secret-message-secrets` Secret from a local
`secrets.env` file (gitignored — same pattern as the repo's `.env`):

```bash
cd k8s/overlays/prod
cp secrets.env.example secrets.env
openssl rand -base64 32   # → IDEMPOTENCY_MASTER_KEY
$EDITOR secrets.env
```

Keys: `REDIS_PASSWORD`, `NATS_USER`, `NATS_PASS`, `IDEMPOTENCY_MASTER_KEY`.
Kustomize appends a content hash to the Secret name, so changing a secret
value and re-applying rolls the Deployments automatically.

For real production use, prefer an external secret store (External Secrets
Operator, Sealed Secrets, or a cloud secret manager) over local files.

## Deploy

```bash
kubectl kustomize k8s/overlays/dev     # render and review
kubectl apply -k k8s/overlays/dev

kubectl -n secret-message-dev get pods
kubectl -n secret-message-dev port-forward svc/secret-message 8080:8080
curl http://localhost:8080/actuator/health
```

Prod is the same with `k8s/overlays/prod` and namespace `secret-message`.
Pin the app image to a release tag via the commented `images:` block in
`k8s/overlays/prod/kustomization.yaml` instead of tracking `latest`.

## Design decisions

### Why not CloudNativePG (or any Postgres)?

CloudNativePG is an operator that manages **PostgreSQL** clusters. This stack
contains no PostgreSQL: Redis is the datastore, chosen in
[ADR-0001](STORAGE_NATS_VS_REDIS.md) because its primitives map 1:1 onto the
three load-bearing operations (atomic GET+DEL self-destruct, atomic INCR with
first-write TTL for the 3-strike counter, per-key TTL auto-expiry). ADR-0001
explicitly evaluated Postgres and rejected it: per-row expiry needs an
external sweep job (`pg_cron`), which defeats the point of TTL. So there is
nothing for CNPG to manage here; adopting it would mean migrating the store
to Postgres first, which ADR-0001 argues against. If that trade-off is ever
revisited, it should get its own ADR.

### Redis is deliberately ephemeral

The Redis Deployment uses `emptyDir` and disables RDB snapshots and AOF
(`--save "" --appendonly no`). Everything Redis holds is TTL-bound ciphertext
for self-destructing messages plus rate-limit counters; persisting it to a
PersistentVolume would put (encrypted) secrets at rest on disk beyond the
app's control and outlive the self-destruct model. Consequences:

- A Redis pod restart loses all stored messages, attempt counters,
  idempotency records, and rate-limit state. Senders must re-create messages.
- This matches current behavior: `compose.yaml` also runs Redis without a
  volume.
- If durability is ever required, use a StatefulSet + PVC with AOF enabled,
  and consciously accept ciphertext at rest (consider encrypted storage
  classes).

`replicas: 1` with `strategy: Recreate` — the app's atomicity guarantees
(ADR-0001) assume a single Redis primary; do not scale this Deployment.

### NetworkPolicies enforce the internal-transport boundary

[ADR-0002](TRANSPORT_NATS_VS_KAFKA.md) keeps core NATS as the internal
transport on the condition that the broker is never reachable from untrusted
networks (plaintext and raw AES keys transit it). In compose this is implicit
(private compose network); in Kubernetes it is explicit:

- `default-deny-ingress` blocks all pod ingress in the namespace.
- Redis :6379 and NATS :4222 accept traffic **only from the app pods**.
- The app :8080 accepts traffic **only from the ingress-controller
  namespace**.
- No Service exposes NATS monitoring (8222); it serves kubelet probes only.

Internal backend clients of `save.msg`/`receive.msg` must run inside the
namespace and get an explicit allow rule in
`k8s/base/network-policies.yaml` — never a NodePort/LoadBalancer on NATS.

### Pod hardening mirrors the compose/Dockerfile model

| compose / Dockerfile | Kubernetes equivalent |
|---|---|
| non-root `appuser` (uid 10001) | `runAsNonRoot` + `runAsUser: 10001` |
| `cap_drop: ALL` | `capabilities.drop: [ALL]` |
| `no-new-privileges:true` | `allowPrivilegeEscalation: false` |
| `mem_limit: 512m` | `resources.limits.memory: 512Mi` |
| `ulimits.core: 0` | not settable per-pod; `docker-entrypoint.sh` runs `ulimit -c 0` |
| — (extra) | `readOnlyRootFilesystem: true` (+ `emptyDir` at `/tmp`), seccomp `RuntimeDefault`, `automountServiceAccountToken: false` |

`JAVA_OPTS` keeps the memory-hardening flags from
[MEMORY_HARDENING.md](MEMORY_HARDENING.md) (no dynamic attach, no remote JMX,
exit instead of heap-dump on OOM). The prod overlay sets
`APP_ENV=production`, so `docker-entrypoint.sh` refuses `DEBUG=true` there.

### Probes

Spring Boot 3 auto-enables the Kubernetes liveness/readiness health groups
when it detects `KUBERNETES_SERVICE_HOST`, so no code or property changes
were needed:

- startup: `/actuator/health` (up to ~2 min for JVM start)
- liveness: `/actuator/health/liveness`
- readiness: `/actuator/health/readiness`

### Client IP and rate limiting behind an Ingress

TLS terminates at the Ingress, matching the "HTTPS terminated at reverse
proxy" model in the HTTP request flow. `ClientIpFilter` and the Bucket4j
rate limiter resolve the client IP from `X-Forwarded-For`, trusting proxies
that match `server.tomcat.remoteip.internal-proxies` (RFC1918 + loopback).
Standard pod CIDRs (e.g. `10.x`) are RFC1918, so an in-cluster ingress
controller is trusted out of the box. If your pod network uses non-RFC1918
addresses, extend that property or rate limiting will bucket every request
under the controller's IP.

### Scaling the app

The prod overlay runs 2 app replicas. This is safe without code changes:
NATS subscribers share queue group `secret-message-workers` (each request
handled once) and all shared state — message store, attempt counters,
idempotency records, rate-limit buckets — lives in Redis behind atomic
operations (ADR-0001). Scale via the replicas patch in
`k8s/overlays/prod/kustomization.yaml`.

## Performance characteristics

Kubernetes does not make an individual request faster — same JVM, same
crypto, same Redis operations, plus an extra Ingress hop. What the
deployment buys is **capacity and availability**, not latency:

- **Throughput scales with app replicas.** All state lives in Redis, so
  the CPU-bound work (AES encrypt/decrypt, request handling) scales
  nearly linearly across replicas. Adding capacity is a one-line
  replicas patch (or, later, an HPA).
- **Availability reads as performance.** Rolling updates behind
  readiness gates make app deploys zero-downtime (compose restarts drop
  traffic); liveness probes restart a wedged JVM; the Service spreads
  load so one GC-pausing pod doesn't stall all traffic.
- **No CPU limit on the app is deliberate.** CPU requests inform the
  scheduler; a CPU *limit* would cause throttling, which hurts JVM tail
  latency badly. Memory is limited (512Mi, matching compose); CPU is not.

The ceiling: Redis and NATS are single instances and deliberately cannot
scale horizontally (ADR-0001's atomicity guarantees assume one Redis
primary). Past a handful of app replicas the data tier becomes the
bottleneck. At the current rate limit (100 requests/day/IP) that ceiling
is far away — but "app tier scales, data tier doesn't" is the accurate
one-line summary. No load tests have been run; performance claims here
are architectural, not empirical.

## Known gaps (production backlog)

Reviewed and accepted for now; address before serious production use:

1. **JVM heap sizing.** `JAVA_OPTS` sets no `-XX:MaxRAMPercentage`, so
   the JVM defaults to 25% of the container limit (~128Mi heap under
   512Mi). Either add `-XX:MaxRAMPercentage=50` (or similar, leaving
   room for metaspace/threads/direct buffers) or accept the conservative
   default knowingly.
2. **No spread or disruption protection.** The 2 prod replicas have no
   `topologySpreadConstraints`/anti-affinity (both can land on one node)
   and no `PodDisruptionBudget` (a node drain can evict both at once).
   This undermines the availability the second replica exists for.
3. **Image pinning is suggested, not enforced.** The base tracks
   `:latest` with `imagePullPolicy: Always`; the prod pin is a
   commented-out `images:` block. Uncomment and pin before any prod
   rollout.
4. **Redis is the availability ceiling.** Single instance with
   `strategy: Recreate` and no persistence: any Redis pod move is a
   brief full outage plus total data loss (accepted by design, but no
   number of app replicas raises availability past it).
5. **Live-cluster validation pending.** Everything so far is offline
   (kustomize render + kubeconform). The critical first live check is
   NetworkPolicy enforcement — see below.

## Offline validation

Performed without a cluster (none of these steps contact an API server):

```bash
# render both overlays (copy secrets.env.example → secrets.env first)
kubectl kustomize k8s/overlays/dev  > /tmp/dev.yaml
kubectl kustomize k8s/overlays/prod > /tmp/prod.yaml

# schema validation, if kubeconform is installed
kubeconform -strict -summary /tmp/dev.yaml /tmp/prod.yaml

# spot checks
grep -c "kind: NetworkPolicy" /tmp/prod.yaml   # 4
grep "runAsUser: 10001" /tmp/prod.yaml         # app hardening present
grep -A3 "name: nats" /tmp/prod.yaml | grep 8222  # must NOT appear in a Service
```

First live deployment should additionally verify: pods Ready, health
endpoint 200 via port-forward, a full create/reveal round-trip over HTTP,
and that a `nats-box` pod in another namespace **cannot** connect to
`nats:4222` (NetworkPolicy actually enforced by the CNI).
