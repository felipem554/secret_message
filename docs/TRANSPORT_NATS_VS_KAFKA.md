# ADR-0002 — Keep NATS as the internal transport, not Kafka

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-11 |
| **Deciders** | Project owner |
| **Supersedes** | — |
| **Superseded by** | — |
| **Related** | ADR-0001 (`docs/STORAGE_NATS_VS_REDIS.md`) |

## Context

The service exposes two transports that both delegate to `SecretMessageService`:

- **HTTP API** (`/api/v1/*`) — public, rate-limited, idempotency-aware.
- **NATS** (`save.msg` / `receive.msg`) — internal-only, used by backend scripts and services.

The NATS interface is **not** an event stream and **not** a store (ADR-0001 keeps
Redis as the store). It is a synchronous **request-reply RPC transport**:

- `save.msg` — client sends plaintext, service replies `{messageId, aeskey}`.
- `receive.msg` — client sends `{messageId, aeskey}`, service replies plaintext.

Three properties of this traffic are load-bearing for the security model:

1. **Sensitive payloads transit the broker.** The `save.msg` request body is the
   plaintext secret. The `save.msg` reply carries the raw AES key. The
   `receive.msg` reply is the decrypted plaintext. Everything that the HTTP path
   protects with TLS + `Cache-Control: no-store` flows through this broker.
2. **Request-reply semantics.** Every request expects exactly one reply, routed
   back to the requesting client only (`NatsService` publishes to
   `msg.getReplyTo()`, a per-request inbox subject).
3. **Exactly-one-worker delivery.** With multiple app replicas, each request must
   be handled by exactly one instance (NATS queue group
   `secret-message-workers`).

The question this ADR addresses: should the internal transport be migrated from
NATS to Apache Kafka?

## Decision

**Keep core NATS as the internal transport. Do not migrate to Kafka.**

## Rationale

The workload is ephemeral point-to-point RPC over sensitive payloads. Core NATS
was designed for exactly that; Kafka was designed for the opposite (durable,
replayable, partitioned logs). The mismatch shows up on every axis that matters
here.

| Requirement | Core NATS | Kafka |
|---|---|---|
| Request-reply | Native protocol primitive. Client `request()` creates a unique inbox subject; server replies to `replyTo`. One line on each side. | No primitive. Must be emulated with request topics + reply topics + `correlation-id` headers (Spring's `ReplyingKafkaTemplate` / listener reply routing). Correlation, timeouts, and reply-topic provisioning become application concerns. |
| Payload persistence | **None.** Core NATS is in-memory pass-through; a message not delivered is gone. Plaintext secrets and AES keys never touch disk. | **Always.** Every record is written to the broker log and fsynced. Plaintext secrets and raw AES keys would be persisted on broker disk and retained until segment deletion. |
| Deletion guarantee | Nothing to delete. | `retention.ms` is not a guarantee: Kafka deletes only whole *closed* segments, so payloads can outlive the retention target by the active-segment lifetime. Compaction/tombstones don't apply (no keys). |
| Reply privacy | Reply goes to a unique per-request inbox subject; no other client can subscribe to it in a default deployment. | Replies land on a reply *topic*. Any consumer with read access to a shared reply topic sees **all** replies — other clients' plaintext and keys. Requires per-client reply topics locked down with ACLs. |
| One-of-N worker delivery | Queue group (`secret-message-workers`). One flag on subscribe. | Consumer group. Equivalent capability — the one axis where Kafka maps 1:1. |
| Latency | Sub-millisecond, in-memory. | Milliseconds+: disk write, batching (`linger.ms`), consumer poll loop. Acceptable, but strictly worse for RPC. |
| Broker footprint | Single ~20 MB binary, starts in milliseconds. | JVM broker + KRaft controller, hundreds of MB, seconds to start. Heavier in dev (compose), CI (Testcontainers startup ×6 test classes), and production. |
| Key-material hygiene (docs/MEMORY_HARDENING.md) | `NatsService` wipes the key `byte[]` immediately after publish; the client library buffers briefly in memory only. | The producer copies serialized bytes into its `RecordAccumulator` batch buffer; those copies are outside application control, and the broker page cache + log hold more copies. Wiping semantics weaken. |

### The deciding factor: durability is a liability here, not a feature

Kafka's core value proposition — durable, replayable, ordered logs — is
precisely what this service must avoid. The whole design (self-destructing
messages, keys never persisted server-side, uniform 404s, heap-dump-verified
key wiping) works to guarantee that secrets exist in as few places, for as
short a time, as possible. Putting plaintext secrets and raw AES keys into a
broker log that:

- persists them to disk,
- replicates them to other brokers (in any HA topology),
- retains them past logical deletion (segment granularity, page cache, replica
  lag), and
- makes them replayable to any consumer added to the topic later,

would silently create the largest secret-at-rest surface in the system —
larger than Redis, whose payloads are at least AES-256-encrypted before they
arrive (ADR-0001, Consequences). Every mitigation available (minutes-level
`retention.ms`, tiny `segment.ms`, broker disk encryption, TLS, strict ACLs,
per-client reply topics, envelope-encrypting payloads under an internal
transport key) is a patch over a storage behavior the design fundamentally
does not want. Core NATS gets the desired property — *nothing at rest* — by
doing nothing.

### What a migration would cost

Surveyed for completeness (this was researched as a concrete plan before being
rejected):

- **Code:** replace `NatsConfig`/`NatsService` with Kafka listener + reply-
  routing infrastructure; ~same line count but with correlation-ID plumbing
  and error-reply handling that NATS gives for free.
- **Tests:** all six Testcontainers-based test classes spin up a NATS container
  because the Spring context requires the connection bean; each would switch to
  a Kafka container with materially slower startup.
- **Ops:** compose files, GHCR compose, docs, and internal clients of
  `save.msg`/`receive.msg` all migrate simultaneously (hard cutover — the
  surface is small but shared).
- **Security review:** new ACL model, reply-topic isolation, retention
  hardening, at-rest encryption for brokers — none of which exist today.

## Alternatives considered

### Apache Kafka — primary alternative

Analyzed above. Kafka is the right tool when the requirements are durability,
replay, audit trails, high-throughput streaming, or fan-out to many independent
consumer groups. None of those are requirements here, and the first two are
actively harmful to the security model. The legitimate reasons to choose Kafka
anyway would be organizational: an existing hardened Kafka platform, team
expertise, or a mandate to standardize on one messaging system. No such driver
exists for this repository.

### NATS JetStream

Would add persistence to NATS itself — same at-rest objection as Kafka, with
no compensating benefit. Rejected for the same reason ADR-0001 rejected it as
a store.

### Redis Pub/Sub or Redis Streams (consolidate on the existing store)

Tempting because it would remove a runtime dependency (the single point in
favor, mirroring ADR-0001's analysis in the other direction). Rejected:

- Redis Pub/Sub is fire-and-forget broadcast — no queue-group equivalent, no
  reply primitive, messages lost if no subscriber is listening.
- Redis Streams adds consumer groups but is *persistent* (same at-rest problem,
  now co-located with the ciphertext store — plaintext and ciphertext on the
  same disk), and request-reply must be emulated just like Kafka.

### gRPC / internal HTTP endpoint

RPC-native and broker-less. Viable, but it changes the client model (internal
scripts currently use `nats` CLI one-liners), loses location transparency and
built-in load balancing across replicas (needs a service mesh or LB), and
introduces a second HTTP surface that must be firewalled from the public one.
More moving parts than the status quo for zero functional gain.

### RabbitMQ

Native request-reply (`reply-to` + correlation ID, RPC pattern) and work
queues — functionally the closest match after NATS. But queues are durable by
default (same at-rest concern, opt-out rather than opt-in), and it would be a
lateral migration: strictly more operational weight than NATS with no new
capability the service needs.

## Consequences

**Accepted:**

- NATS remains a second runtime dependency alongside Redis (already accepted in
  ADR-0001).
- The internal transport offers no durability: if no app replica is subscribed,
  an internal request is dropped and the client times out. This is the correct
  behavior for RPC over secrets — clients retry.
- NATS access control remains the deployment's responsibility: the broker must
  stay unreachable from public networks, with credentials set via
  `NATS_USER`/`NATS_PASS` (see `docs/RATE_LIMITING.md` §NATS note).

**Avoided:**

- Plaintext secrets and raw AES keys persisted, replicated, and replayable on
  broker disks.
- Correlation-ID/reply-topic plumbing and its failure modes (orphaned replies,
  shared-reply-topic leakage, timeout tuning).
- A heavier broker in dev, CI, and production.
- A cross-cutting migration touching every integration test, both compose
  files, all docs, and every internal client.

## When to revisit

This decision flips if **any** of the following becomes true:

- The internal interface's requirements change from RPC to **event streaming** —
  e.g. an audit/analytics pipeline over message-lifecycle *events* (created,
  revealed, expired — metadata only, never payloads). Kafka becomes a
  reasonable fit for that new traffic, though it should be added as a separate
  metadata-only event bus, not as a replacement for the RPC path.
- The organization deploys a hardened, access-controlled Kafka platform and
  mandates consolidation. In that case the at-rest mitigations listed above
  (envelope encryption of payloads, per-client reply topics, minutes-level
  retention with small segments, encrypted broker volumes) are the minimum bar
  for migrating this traffic, and this ADR should be superseded by one that
  specifies them.
- Internal clients need **guaranteed delivery** of create/reveal requests
  (fire-and-forget with durable handoff rather than synchronous RPC). That is a
  contract change, not a transport swap, and would need its own design covering
  how a durable request queue avoids persisting plaintext.

Until one of those is on the roadmap, this ADR is the binding decision.

## References

- ADR-0001 `docs/STORAGE_NATS_VS_REDIS.md` — Redis vs NATS JetStream as the
  message store; establishes the two-dependency runtime this ADR keeps.
- `docs/MEMORY_HARDENING.md` — key-material wiping at the transport boundary
  (`NatsService`), which producer-side batching in Kafka would weaken.
- `docs/HTTP_API_DESIGN.md` — scope note: NATS is internal-only; HTTP is the
  public interface.
- `src/main/java/.../service/NatsService.java` — request-reply handlers and the
  `secret-message-workers` queue group.
