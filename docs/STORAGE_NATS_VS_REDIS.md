# ADR-0001 — Use Redis as the message store, not NATS JetStream

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-05-04 |
| **Deciders** | Project owner |
| **Supersedes** | — |
| **Superseded by** | — |

## Context

The service stores two kinds of state per secret message:

1. `messages:<id>` — the encrypted payload, with a server-configured TTL (default 2 days, governed by `app.auto-delete-days`).
2. `attempts:<id>` — an integer counter incremented on every wrong-key reveal, with the same TTL as its parent message. The message is deleted when the counter reaches `app.max-tries` (default 3).

The HTTP API also adds a third kind:

3. `idempotency:<uuid>` — JSON record with body hash, message id, and AES key encrypted under the master idempotency key (MIEK), TTL matched to the message TTL.

Three operations are load-bearing for the security model:

- **Atomic delete-after-read** on `messages:<id>`. The message must be read and deleted in a single observable step so two concurrent reveal attempts cannot both succeed. Without atomicity, the one-shot self-destruct guarantee breaks under concurrent HTTP requests.
- **Atomic increment with first-write TTL** on `attempts:<id>`. The counter must increment under contention without losing updates and must inherit a TTL on its first write. Without atomicity, the 3-strike cap drifts upward under load — an attacker issuing parallel requests gets more tries than policy allows.
- **Per-key TTL** with second-or-better granularity for all three key types.

The service already runs NATS as its internal transport. The architectural question this ADR addresses: should NATS JetStream's KV / Object Store also serve as the message backend, replacing Redis?

## Decision

**Keep Redis as the message store. Keep NATS as the internal transport. Do not consolidate.**

## Rationale

Redis primitives map directly onto the three operations above. NATS JetStream KV does not.

| Operation | Redis | NATS JetStream KV |
|---|---|---|
| Atomic GET + DEL | Single Lua script. Idiomatic. | Two operations (`Get`, `Delete`). No native transaction; requires optimistic concurrency via `revision` matching, with retry loop on conflict. |
| Atomic counter | `INCR` is the canonical example for the data model. | No native increment. Read-modify-write with revision-based CAS; lost-update window if not retried correctly. |
| First-write TTL on counter | `SET key value EX <ttl> NX`, or `INCR` + `EXPIRE` in a single pipeline. | Bucket-level `MaxAge` only on the KV API; per-message TTL added in NATS 2.10 but not consistently exposed at the KV layer. Cannot easily say "this counter inherits the parent message's expiry." |
| Per-key TTL | Native, per-key, second granularity. | Per-bucket only on KV; per-key TTL requires either splitting buckets per TTL value or moving to per-message TTL on the underlying stream — both add operational complexity. |

Both atomicity requirements *can* be implemented on NATS JetStream KV with optimistic-concurrency loops (read revision, write conditionally, retry on conflict). That works, but:

- It is non-trivial code. Redis gives the same guarantee in one line.
- Retry loops have failure modes (livelock under heavy contention) that need their own bounds and metrics.
- It moves business-critical correctness into application code, where Redis would handle it in the data layer with primitives that have been hardened for two decades.

The atomicity story is the deciding factor. The 3-strike attempt counter and the one-shot delete-after-read are the two security guarantees the service rests on. Both depend on the storage layer's atomic primitives. Choosing a backend that requires us to build atomicity in application code is the wrong direction for this codebase.

## Alternatives considered

### NATS JetStream KV — primary alternative

Already analyzed above. NATS is excellent at what it was designed for (event streaming, pub/sub, durable subjects). It is not designed for the workload "many short-lived keyed records with per-key TTL and atomic counters." Forcing it into that role would work but at higher cost than the status quo.

The single point in NATS's favor is **one fewer infrastructure dependency**. That is real but not enough to outweigh the loss of native atomicity.

### DynamoDB

Strong technical fit (native TTL, `UpdateItem ADD` for atomic counters, conditional writes for atomic delete-after-read). Rejected because it is AWS-only and the project does not require cloud lock-in. Worth revisiting if the deployment target ever changes.

### KeyDB / Dragonfly

Redis-protocol-compatible, multi-threaded forks. Drop-in replacements for Redis if performance becomes the constraint. Not chosen now because Redis itself is not the bottleneck. Path remains open: any Redis-API code we write today works against KeyDB or Dragonfly with no change.

### Cassandra / ScyllaDB

Per-cell TTL native, atomic counters as a separate column type. Atomic-delete-after-read needs Lightweight Transactions (Paxos), 10–100× slower than regular writes. Operational overhead larger than Redis. Not justified by this workload.

### Memcached

Has TTL and `incr`. Rejected because it lacks an atomic GET + DELETE primitive and its eviction is best-effort, not TTL-enforced. Either gap breaks the security model.

### PostgreSQL

Atomicity excellent, TTL story bad. Per-row expiry needs an external job (`pg_cron` or scheduled cleanup). Defeats the point of TTL — that the data layer enforces it without application help.

## Consequences

**Accepted:**

- Two infrastructure dependencies in the runtime: Redis and NATS. Slightly more operational overhead than a single-store deployment.
- Redis OSS lacks built-in encryption-at-rest. Mitigated because all payloads are already AES-256-encrypted client-side before they reach Redis. The MIEK-encrypted idempotency records are also already encrypted at the application layer. At-rest encryption on Redis would be defense-in-depth, not the primary control.
- Redis Sentinel or Redis Cluster needed for HA. Acceptable given Redis's mature operational story.

**Avoided:**

- Application-level retry loops to simulate atomic operations.
- Lost-update bugs in the 3-strike counter under concurrent HTTP load.
- Race conditions in the one-shot self-destruct path.
- Migration of complex TTL semantics into application code.

**Deferred to follow-up if needed:**

- At-rest encryption on Redis (Redis Enterprise or transparent disk encryption).
- HA topology decisions (Sentinel vs Cluster vs managed offering).

## When to revisit

This decision flips if **any** of the following becomes true:

- We add features that need pub/sub or streaming **on the stored data** itself (e.g. "notify subscribers when a message is revealed"). NATS JetStream becomes the obvious fit because it is event-native. Redis Streams would also work but is the smaller half of Redis's API surface.
- The deployment must run in an environment where Redis is unavailable but NATS is (some edge or air-gapped setups).
- At-rest encryption becomes a hard compliance requirement *and* Redis Enterprise / managed Redis with encryption is not available *and* application-layer encryption is not considered sufficient. NATS JetStream's built-in at-rest encryption then becomes the cheapest path.

Until one of those is on the roadmap, this ADR is the binding decision.

## References

- `docs/HTTP_API_DESIGN.md` §9 — full storage schema across all four key types.
- `docs/MEMORY_HARDENING.md` — covers in-memory protection of MIEK and per-message AES keys, complementing the at-rest analysis here.
