# Kafka Internal Transport — Study Implementation Plan

| Field | Value |
|---|---|
| **Status** | Planned (study branch: `feature/kafka-transport-study`) |
| **Date** | 2026-07-12 (rev. 2 — full NATS substitution, two-app architecture) |
| **Relation to ADR-0002** | This branch **replaces NATS entirely**, reversing ADR-0002 (`docs/TRANSPORT_NATS_VS_KAFKA.md`) for study purposes. On merge to this branch's lineage, ADR-0002 must be marked *Superseded by* a new ADR-0003 that records the reversal and its educational driver. On `main`, ADR-0002 remains binding. |

## Purpose and framing

ADR-0002 concluded that Kafka is a poor fit for this workload: the internal
interface is ephemeral request-reply RPC over sensitive payloads, and Kafka's
defining property — a durable, replicated, replayable log — is a liability
for a service whose entire design minimizes where secrets exist and for how
long. **This implementation is acknowledged to be non-optimal. It is done for
study purposes:**

- to experience the request-reply emulation (correlation IDs, reply topics,
  timeouts) that NATS gives natively;
- to implement, hands-on, the at-rest mitigations ADR-0002 listed as "the
  minimum bar" (envelope encryption, per-client reply isolation, aggressive
  retention, TLS/SASL/ACLs);
- to restructure the monolith into **two cooperating apps** whose load Kafka
  distributes, and measure what that buys and costs;
- to measure the real footprint difference (broker size, test startup,
  latency) rather than only reasoning about it.

## Goals

1. **Complete removal of NATS** — dependency, config, service, compose
   services, tests, docs references. No coexistence, no feature flag.
2. **Two-app architecture**: the service splits into a **save app** and a
   **receive app**, each subscribed to its own request topic, so the two
   workloads scale and parallelize independently.
3. **No plaintext secret and no raw AES key ever written to a Kafka log** —
   the hard security invariant (see Security design).
4. Integration tests with a Kafka Testcontainer preserving the current
   behavioral contract (round-trip, self-destruct, 3-strike, uniform errors).
5. Documented findings, feeding ADR-0003.

## Non-goals

- Keeping any NATS fallback or compatibility path.
- Production hardening of the Kafka broker beyond what the study needs to
  demonstrate the security model.

## Architecture — two apps, split by operation

The single Spring Boot app splits along the write/read seam that already
exists in the domain (create vs reveal). One codebase, one Docker image, two
runtime roles selected by `APP_ROLE`:

| | **Save app** (`APP_ROLE=save`) | **Receive app** (`APP_ROLE=receive`) |
|---|---|---|
| Kafka subscription | `secret.save.requests` (group `save-workers`) | `secret.receive.requests` (group `receive-workers`) |
| HTTP endpoints | `POST /api/v1/messages` (+ idempotency) | `POST /api/v1/messages/reveal` |
| Business logic | `SecretMessageService.createSecretMessage` | `SecretMessageService.getEncryptedMessageById` (attempt counter, atomic delete) |
| Shared state | Redis (messages/attempts/idempotency/ratelimit — unchanged schema) | same Redis |

```
Internal client ──produce──▶ secret.save.requests ──▶ [save app × N]    ──▶ Redis
Internal client ──produce──▶ secret.receive.requests ▶ [receive app × N] ─▶ Redis
      ▲                                                      │
      └────────── secret.replies.<client-id> ◀───────────────┘
                  (correlation-id matched)

Public client ─▶ reverse proxy ─┬─ /api/v1/messages        ─▶ save app
                                └─ /api/v1/messages/reveal ─▶ receive app
```

Design points:

- **Single codebase, role-gated beans.** Controllers, Kafka listeners, and
  the idempotency components activate via `@ConditionalOnProperty` on
  `app.role`. One image keeps the build, CI, and GHCR pipeline unchanged;
  compose runs two containers from it. (Alternative considered: a Gradle
  multi-module split into `core` + two boot apps — cleaner separation but a
  much larger refactor; can be a later study step.)
- **How the load actually distributes.** Two apps on two topics separate the
  *workloads* (create traffic can no longer starve reveal traffic and vice
  versa). *Within* each workload, parallelism comes from Kafka partitions +
  consumer groups: each request topic gets **6 partitions**, and replicas of
  an app (`docker compose up --scale app-save=2`) join its consumer group and
  split partitions. This replaces the NATS queue group
  `secret-message-workers`, per role.
- **Correctness under concurrency is unchanged.** Both apps hit the same
  Redis, and reveal-once / 3-strike semantics are enforced by atomic Redis
  operations (`RedisCacheManager.deleteIfPresent`), not by app-local state —
  so N parallel consumers are as race-safe as today's replicas.
- **Rate limiting stays global**: Bucket4j state is in Redis, so the per-IP
  limit spans both apps. The reverse proxy routes by path; `ClientIpFilter`
  behavior is identical in both roles.
- **Ordering caveat (finding to document):** partitioning by random key means
  no ordering between a save and a subsequent receive — but the contract
  never needed ordering: a receive for a not-yet-saved id is just the uniform
  "not available" error, and the client retries.

## Topic model — request-reply emulation

NATS request-reply must be rebuilt from parts (a core learning target):

| NATS concept (removed) | Kafka replacement |
|---|---|
| `save.msg` subject | `secret.save.requests` topic (6 partitions) |
| `receive.msg` subject | `secret.receive.requests` topic (6 partitions) |
| Per-request inbox reply subject | Per-client reply topic `secret.replies.<client-id>` + `correlation-id` header |
| Queue group `secret-message-workers` | Consumer groups `save-workers` / `receive-workers` |
| Client `request()` | Produce with `reply-topic` + `correlation-id` headers, poll own reply topic, timeout client-side |
| Server reply to `replyTo` | App produces to the topic named in the `reply-topic` header |

The server validates the requested reply topic against the
`secret.replies.*` namespace before producing (a client must not redirect
replies to an arbitrary topic).

## Security design — keeping the stream secure internally

The threat ADR-0002 identified is that Kafka **persists, replicates, and
replays** everything. The defense is layered: make what reaches the broker
worthless, make the broker hard to reach, and make what it stores
short-lived.

**Layer 1 — Envelope encryption (the invariant).** Neither app ever produces
plaintext or raw key material to Kafka. A server-held **Internal Transport
Encryption Key** (ITEK, Base64 32-byte AES key via `INTERNAL_TRANSPORT_KEY`,
same pattern as `IDEMPOTENCY_MASTER_KEY` / `IdempotencyKeyVault`) encrypts,
with a fresh IV per record:

- the `save` request body (the internal client encrypts before producing),
- the `save` reply (`{messageId, aeskey}` — the raw AES key is the most
  sensitive field in the system),
- the `receive` request (contains the AES key) and the `receive` reply
  (the decrypted plaintext).

Consequence: even though the Kafka log is durable and replayable, every
record in it is AES-256 ciphertext under a key the broker never sees. A
compromised broker disk, a replica, a page-cache remnant, or a late-added
rogue consumer yields nothing without the ITEK. This also neutralizes the
producer-side `RecordAccumulator` objection from ADR-0002 /
`MEMORY_HARDENING`: the producers' internal buffers only ever hold
ciphertext. Encryption reuses `CryptoUtil` (byte[]-only API) and matches the
existing `wipe()` discipline from the removed `NatsService`. Both apps hold
the same ITEK.

Trade-off to document: internal clients now need the ITEK — the
`nats request save.msg "secret"` one-liner ergonomics are lost. That is the
price of putting RPC over a log.

**Layer 2 — Encryption in transit.** Broker listeners are TLS-only
(`SASL_SSL://`); `PLAINTEXT` listeners disabled, inter-broker TLS included.

**Layer 3 — Authentication.** SASL/SCRAM-SHA-512 (or mTLS) with distinct
principals: `app-save`, `app-receive`, and one per internal client. No
anonymous access; `allow.everyone.if.no.acl.found=false`.

**Layer 4 — Authorization (ACLs).** Least privilege per principal:

| Principal | Allowed |
|---|---|
| `app-save` | Read `secret.save.requests` (group `save-workers`); Write `secret.replies.*` |
| `app-receive` | Read `secret.receive.requests` (group `receive-workers`); Write `secret.replies.*` |
| client `X` | Write `secret.*.requests`; Read **only** `secret.replies.X` with its own group |

The two-app split tightens ACLs beyond the monolith: the save app cannot even
read receive traffic, and vice versa. No client can read another client's
replies. `auto.create.topics.enable=false` so ACLs cannot be bypassed by
minting topics.

**Layer 5 — Minimize time-at-rest.** Per-topic: `retention.ms=60000`,
`segment.ms=60000`, small `segment.bytes`, `cleanup.policy=delete`,
replication factor 1 for the study. Documented honestly: deletion happens at
closed-segment granularity, so this is harm reduction, **not** a guarantee —
which is exactly why Layer 1 is the invariant.

**Layer 6 — Isolation.** Broker only on the internal compose network (no
host port mapping by default), encrypted volume in any real deployment, same
container hardening baseline as the app services (`cap_drop`,
`no-new-privileges`, memory limits).

### What this cannot fix (to be written up in the findings)

- Deletion is best-effort; ciphertext may outlive `retention.ms`.
- The ITEK is a second server-held master key: a leaked ITEK + retained log
  segments = retroactive decryption of the retention window. NATS had no
  equivalent exposure because nothing was at rest.
- Latency and operational weight are strictly worse for this RPC shape.

## Implementation plan

### Phase 0 — Branch and docs (this change)

- [x] Branch `feature/kafka-transport-study`.
- [x] This plan document (rev. 2: full substitution, two apps).
- [x] README note stating the Kafka implementation is acknowledged as
      non-optimal (per ADR-0002) and exists for study purposes.

### Phase 1 — Remove NATS, add Kafka infrastructure

- `build.gradle`: **remove** `io.nats:jnats`; add
  `org.springframework.kafka:spring-kafka` and
  `testImplementation 'org.testcontainers:kafka'`.
- **Delete** `config/NatsConfig.java`, `service/NatsService.java`,
  `NatsServiceIntegrationTest.java`; strip the NATS container from every
  remaining Testcontainers test class (they only spun it up because the
  Spring context required the connection bean).
- `compose.yaml` / `compose.ghcr.yaml`: **remove** `nats` and `nats-box`
  services and the NATS env vars; add a single-node KRaft broker
  (`apache/kafka`), internal network only, SASL_SSL listener, ACLs enabled,
  auto-create off; an init step provisions the topics (6 partitions each,
  Layer-5 retention) and the SCRAM principals/ACLs; add `kafka-client` box
  container for manual testing (replaces nats-box).
- `application.properties`: **remove** `nats.server.url`; add `app.role`
  (`APP_ROLE`, no default — fail fast if unset), `spring.kafka.*`
  (bootstrap, security protocol, SASL), `app.internal-transport-key`
  (`INTERNAL_TRANSPORT_KEY`, fail-fast validation like the idempotency key).

### Phase 2 — Two-app split + transport code

- `config/AppRoleConfig` — validates `app.role` ∈ {save, receive} at startup.
- Role-gate existing HTTP beans: `MessageController` splits into
  `SaveMessageController` (create + `IdempotencyService`, save role) and
  `RevealMessageController` (reveal, receive role); filters and
  `GlobalExceptionHandler` stay common.
- `config/KafkaTransportConfig.java` — producer/consumer factories
  (byte-array serializers only — no String serializers near key material),
  role-specific listener container (concurrency = partitions per instance
  share), error handler.
- `service/KafkaSaveService.java` / `service/KafkaReceiveService.java` —
  mirror the removed `NatsService` handlers one-to-one: same size/format
  validations, same error-reply contract (`{"error": "..."}`,
  ciphertext-wrapped), same `MAX_ATTEMPTS_MESSAGE` behavior, same `wipe()`
  discipline, delegating to the untouched `SecretMessageService`.
- ITEK vault class reusing the `IdempotencyKeyVault` pattern (decode at
  startup, hold as `byte[]`, envelope encrypt/decrypt via `CryptoUtil`, zero
  intermediates).
- Reply-topic namespace validation before producing any reply.
- `docker-entrypoint.sh`: pass `APP_ROLE` through; compose defines `app-save`
  and `app-receive` services from the same image, and the reverse-proxy
  routing note lands in the README.

### Phase 3 — Internal client helper

- `scripts/kafka-client.sh` (or a small CLI helper) performing the client
  side: envelope-encrypt request → produce with `reply-topic` +
  `correlation-id` headers → poll own reply topic → match correlation id →
  decrypt reply. Replaces the `nats request` one-liners and serves as
  executable documentation of the client contract.

### Phase 4 — Tests

- `KafkaSaveServiceIntegrationTest` + `KafkaReceiveServiceIntegrationTest` —
  Kafka Testcontainer via `@DynamicPropertySource` (same pattern the NATS
  tests used), each booting the app in its role:
  - save → receive round-trip across the two roles (UTF-8 payload);
  - self-destruct on first receive;
  - 3-strike deletion and `MAX_ATTEMPTS_MESSAGE` contract;
  - error contract for empty/oversized/malformed requests;
  - **log-inspection test**: consume the raw topics from offset 0 and assert
    no plaintext fragment and no raw AES key bytes appear in any record
    (the Layer-1 invariant, tested);
  - reply isolation: a consumer on a different reply topic never sees the
    reply;
  - **parallel distribution test**: two consumers in one group receive
    disjoint partition assignments and share a burst of requests.
- Remaining test classes (`MessageApiIntegrationTest`, rate-limit,
  idempotency, service, cache) run with the appropriate `app.role` and no
  NATS container; HTTP tests split or parameterize by role.
- Record Testcontainers startup time before/after (NATS vs Kafka — a data
  point ADR-0002 estimated; the study measures it).

### Phase 5 — Docs, ADR, findings

- **ADR-0003** — "Replace NATS with Kafka as the internal transport (study)":
  records the reversal, its educational driver, and the mitigations as
  binding requirements; marks ADR-0002 *Superseded by ADR-0003* on this
  branch.
- `README.md`: rewrite the NATS Interface section as the Kafka interface
  (topics, client helper, two-app table, proxy routing), update
  Configuration/Ports/Troubleshooting; keep the study disclaimer.
- `CLAUDE.md`: update architecture, key classes, subjects→topics table,
  config, ports.
- `docs/MEMORY_HARDENING.md` addendum: envelope encryption at the producer
  boundary; re-run `scripts/heap-scan.sh` against **both** apps.
- Append a **Findings** section here: measured latency/startup deltas,
  lines-of-code vs the removed `NatsService`, residual risks.

## Verification checklist

- [ ] `grep -ri nats` over `src/`, compose files, and scripts returns nothing
      (docs keep historical ADR references only).
- [ ] `./gradlew test` fully green with no NATS containers anywhere.
- [ ] `docker compose up`: `app-save` + `app-receive` + Kafka + Redis;
      HTTP create (via save app) → HTTP reveal (via receive app) round-trips.
- [ ] Kafka path: client helper save → receive round-trips across the two
      apps; scaling one role (`--scale app-save=2`) splits partitions.
- [ ] Manual check: dump raw topics (`kafka-console-consumer` from offset 0)
      and confirm only ciphertext.
- [ ] Heap-scan run against both roles with the Kafka path exercised
      (`docs/MEMORY_HARDENING.md` procedure).

## References

- `docs/TRANSPORT_NATS_VS_KAFKA.md` — ADR-0002, the decision this branch
  deliberately reverses for study; source of the mitigation "minimum bar".
- `docs/STORAGE_NATS_VS_REDIS.md` — ADR-0001; Redis stays the store, and its
  atomic operations are what keep the two-app split race-safe.
- `docs/MEMORY_HARDENING.md` — key-wiping discipline the Kafka boundary must
  match.
- Spring for Apache Kafka — listener containers, reply routing, SASL/SSL
  client config.
