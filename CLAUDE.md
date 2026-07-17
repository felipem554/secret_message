# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
./gradlew build

# Run locally (requires Redis and NATS running)
./gradlew bootRun

# Run full stack with Docker Compose
docker compose up -d
docker compose logs -f app
docker compose down

# Run with debug mode
DEBUG=true docker compose up -d

# Health check
curl http://localhost:8080/actuator/health

# Deploy to Kubernetes (Kustomize manifests; see docs/KUBERNETES.md)
cp k8s/overlays/dev/secrets.env.example k8s/overlays/dev/secrets.env  # then edit
kubectl apply -k k8s/overlays/dev
```

## Tests

```bash
# All tests (uses Testcontainers — requires Docker socket at /var/run/docker.sock)
./gradlew test

# Single test class
./gradlew test --tests MessageApiIntegrationTest
./gradlew test --tests SecretMessageServiceTest

# Specific test method
./gradlew test --tests "SecretMessageServiceTest.utf8RoundTripTest"
```

Tests use Testcontainers to spin up real Redis and NATS instances via `@DynamicPropertySource`. The Gradle test task passes `api.version=1.44` as a JVM system property to satisfy Docker Engine 25+ which dropped support for API versions below 1.40.

## Architecture

**Spring Boot 3** application with two transport interfaces:

- **HTTP API** (`/api/v1/*`) — the public interface. Clients use this.
- **NATS** (`save.msg` / `receive.msg`) — internal-only. Backend scripts and services use this.

Both transports delegate to `SecretMessageService`. Neither owns business logic.

### Request flow — HTTP (primary)

```
Public client
  → HTTPS (TLS terminated at reverse proxy)
  → ClientIpFilter  (resolve trusted client IP from X-Forwarded-For)
  → RateLimitFilter (100 req/day/IP, Redis-backed Bucket4j)
  → MessageController.create / .reveal
  → [IdempotencyService] (duplicate-create prevention on create)
  → SecretMessageService
  → AES-256 encrypt/decrypt → Redis (atomic operations) → TTL
```

### Request flow — NATS (internal)

```
Internal client → NATS save.msg (plaintext)
  → NatsService → SecretMessageService
  → AES-256 encrypt → Redis (TTL: auto-delete-days)
  ← {messageId, aeskey}

Internal client → NATS receive.msg ({messageId, aeskey})
  → NatsService → SecretMessageService
  → Redis fetch → AES-256 decrypt → Redis atomic delete
  ← plaintext
```

### Key classes

| Class | Package | Role |
|-------|---------|------|
| `MessageController` | `controller` | HTTP create + reveal endpoints |
| `NatsService` | `service` | NATS subject subscribers (internal) |
| `SecretMessageService` | `service` | Business logic: encrypt, decrypt, attempt-counter, atomic-delete |
| `RedisCacheManager` | `cache` | Redis primitives including `deleteIfPresent` (race-safe) |
| `CryptoUtil` | `utils` | AES-256-CBC; byte[]-only key API (memory hardening) |
| `IdempotencyKeyVault` | `idempotency` | Holds MIEK; encrypt/decrypt per-message AES keys for idempotency storage |
| `WipingBase64Serializer` | `utils` | Writes byte[] keys as Base64 JSON, zeroes the buffer after writing |
| `IdempotencyService` | `idempotency` | Redis read/write of idempotency records |
| `ClientIpFilter` | `filter` | Resolves trusted client IP from X-Forwarded-For |
| `RateLimitFilter` | `filter` | Bucket4j 100/day/IP rate limiting |
| `GlobalExceptionHandler` | `exception` | Maps exceptions to HTTP responses; Micrometer counters for reveal failures |

### NATS subjects (internal)

| Subject | Input | Output |
|---------|-------|--------|
| `save.msg` | plaintext string | `{"messageId":"...", "aeskey":"..."}` |
| `receive.msg` | `{"messageId":"...", "aeskey":"..."}` | plaintext string |

### HTTP endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/v1/messages` | Create secret (optional `Idempotency-Key` header) |
| `POST` | `/api/v1/messages/reveal` | Read once and delete |
| `GET` | `/actuator/health` | Spring Boot Actuator health |

### Security model

- Each message gets a unique random AES-256 key and IV.
- The service **never stores the per-message decryption key in plaintext**.
- Key material flows internally as `byte[]` only (never `String`/`SecretKey`) and is zeroed at the transport boundary after the response is written; see `docs/MEMORY_HARDENING.md` and `scripts/heap-scan.sh`.
- Idempotency records store the AES key encrypted under a server-held master key (`IDEMPOTENCY_MASTER_KEY`).
- Message self-destructs on first successful reveal (atomic GET+DEL).
- Three failed decryption attempts trigger deletion (global counter, not per-IP).
- All reveal failure cases return identical HTTP 404 (uniform response, no information leakage).

See `docs/MEMORY_HARDENING.md` for the operational key-material hardening plan and `docs/JVM_MEMORY_SECURITY_PRIMER.md` for the JVM memory primer.

### Redis key schema

| Key | Purpose | TTL |
|-----|---------|-----|
| `messages:<id>` | Encrypted payload | `app.auto-delete-days` |
| `attempts:<id>` | Wrong-key attempt counter | Same as message (set on first increment) |
| `idempotency:<uuid>` | Idempotency record (AES key encrypted under MIEK) | Same as message |
| `ratelimit:<ip>` | Bucket4j rate limit state | 24 h |

## Configuration

| Property / Env var | Default | Purpose |
|--------------------|---------|---------|
| `IDEMPOTENCY_MASTER_KEY` | dev fallback | Base64-encoded 32-byte AES key. Generate: `openssl rand -base64 32` |
| `NATS_URL` | `nats://localhost:4222` | NATS broker URL |
| `NATS_USER` / `NATS_PASS` | — | NATS auth (optional) |
| `SPRING_REDIS_HOST/PORT/PASSWORD` | localhost:6379 | Redis connection |
| `app.auto-delete-days` | `2` | Message TTL in days |
| `app.max-tries` | `3` | Max failed decryption attempts |
| `app.max-message-size` | `1048576` | Max message size in bytes (1 MB) |
| `app.rate-limit.requests-per-day` | `100` | HTTP API rate limit per client IP |
| `JAVA_OPTS` | attach/JMX/heap-dump hardening flags | Extra JVM flags passed by `docker-entrypoint.sh` |
| `APP_ENV` | `development` | Startup fails fast when `DEBUG=true` and `APP_ENV=production` |

## Ports

| Service | Port |
|---------|------|
| Spring Boot HTTP | 8080 |
| JVM debug (`DEBUG=true`) | 5005 |
| Redis | 6379 |
| NATS clients | 4222 |
| NATS monitoring | 8222 |

## Design documents

| File | Contents |
|------|----------|
| `docs/HTTP_API_DESIGN.md` | Full HTTP API design: endpoints, error model, idempotency, rate limiting, security decisions |
| `docs/MEMORY_HARDENING.md` | Operational JVM/OS key-material hardening plan and verification checklist |
| `docs/JVM_MEMORY_SECURITY_PRIMER.md` | Didactic JVM memory, GC, leak, and secret-handling primer |
| `docs/STORAGE_NATS_VS_REDIS.md` | ADR-0001: why Redis was chosen over NATS JetStream as the message store |
| `docs/TRANSPORT_NATS_VS_KAFKA.md` | ADR-0002: why NATS was kept over Kafka as the internal transport |
| `docs/RATE_LIMITING.md` | Rate limiting options and Bucket4j implementation notes |
| `docs/RATE_LIMIT_RECOVERY.md` | Why the old rate-limit reset command failed and the correct procedure |
| `docs/KUBERNETES.md` | Kubernetes deployment guide: Kustomize layout, ephemeral-Redis and NetworkPolicy rationale, why CloudNativePG doesn't apply |
