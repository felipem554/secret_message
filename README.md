# Self-Destructing Secret Message Service

A secure service for sharing encrypted messages that self-destruct after being read. Built with Spring Boot, Redis, and NATS.

Messages are encrypted with AES-256 before storage. The server never persists the decryption key — only the client holds it. A message deletes itself on first successful read, or after three failed decryption attempts, or after a server-configured TTL (default 2 days).

## Table of Contents

- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [HTTP API](#http-api)
- [NATS Interface (internal)](#nats-interface-internal)
- [Configuration](#configuration)
- [Security](#security)
- [Development](#development)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)

## Architecture

The service exposes two transports that both delegate to a single business-logic layer. Neither transport owns any crypto or storage logic.

- **HTTP API** (`/api/v1/*`) — the public interface. Requests pass through `ClientIpFilter` (resolves the trusted client IP from `X-Forwarded-For`) and `RateLimitFilter` (100 req/day/IP, Redis-backed) before reaching `MessageController`.
- **NATS** (`save.msg` / `receive.msg`) — an internal-only transport. Backend services and scripts can publish to these subjects directly; they are not rate-limited or exposed to the public network.

Both transports call `SecretMessageService`, which performs AES-256 encryption/decryption, tracks failed-attempt counters, and issues atomic Redis operations. On the HTTP create path, `IdempotencyService` sits in front to prevent duplicate messages on client retries. All state lives in Redis (`messages:*`, `attempts:*`, `idempotency:*`, `ratelimit:*`), each key carrying a TTL.

| Layer | Components | Responsibility |
|-------|------------|----------------|
| Edge (HTTP only) | `ClientIpFilter`, `RateLimitFilter` | Trusted-IP resolution, per-IP rate limiting |
| Transport | `MessageController` (HTTP), `NatsService` (internal) | Request/response handling — no business logic |
| Business logic | `SecretMessageService`, `IdempotencyService` | Encrypt/decrypt, attempt counting, atomic delete, duplicate prevention |
| Storage | Redis | Encrypted payloads + counters + idempotency records, all TTL-bound |

**NATS is an internal transport only.** Public clients use the HTTP API; backend services and scripts can still publish to `save.msg` / `receive.msg` directly.

### Request flow — HTTP

**Create** — `POST /api/v1/messages {"message": "..."}`

1. `IdempotencyService` checks for a prior record when an `Idempotency-Key` is supplied.
2. `SecretMessageService.createSecretMessage` encrypts the payload with AES-256 under a fresh random key + IV.
3. The ciphertext is stored at `messages:<id>` with a TTL of `app.auto-delete-days`.
4. Responds `201` with `{"messageId": "...", "aesKey": "..."}` (the server never persists the key).

**Reveal** — `POST /api/v1/messages/reveal {"messageId": "...", "aesKey": "..."}`

1. `SecretMessageService.getEncryptedMessageById` fetches the ciphertext from Redis.
2. It decrypts with the supplied key, then atomically deletes the message on success.
3. Responds `200` with `{"message": "..."}`. A wrong key increments `attempts:<id>`; three failures delete the message. All failures return a uniform `404`.

## Quick Start

### Prerequisites

- Docker 20.10+ and Docker Compose 2.0+
- Java 21 (local development only)

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env and set all passwords, and generate a master idempotency key:
openssl rand -base64 32    # → paste as IDEMPOTENCY_MASTER_KEY
```

### 2. Start the stack

```bash
docker compose up -d
docker compose logs -f app
```

### 3. Smoke test

```bash
# Health check
curl http://localhost:8080/status

# Create a secret
curl -s -X POST http://localhost:8080/api/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{"message":"my secret"}' | tee /tmp/secret.json

# Reveal it (copy messageId and aesKey from above)
curl -s -X POST http://localhost:8080/api/v1/messages/reveal \
  -H 'Content-Type: application/json' \
  -d "{\"messageId\":\"$(jq -r .messageId /tmp/secret.json)\",\"aesKey\":\"$(jq -r .aesKey /tmp/secret.json)\"}"
```

## HTTP API

Base path: `/api/v1`

All requests and responses use `Content-Type: application/json`. All responses include `Cache-Control: no-store`.

### `POST /messages` — Create a secret

**Request:**

```http
POST /api/v1/messages
Content-Type: application/json
Idempotency-Key: <uuid>   (optional)

{"message": "the secret payload"}
```

The optional `Idempotency-Key` header prevents duplicate messages on client retries. If the same key is submitted with the same body a second time, the original `messageId` and `aesKey` are returned (`duplicate: true`) without creating a new message. Reusing the key with a different body returns `409 Conflict`.

**Success responses:**

| Status | When |
|--------|------|
| `201 Created` | New message created |
| `200 OK` + `duplicate: true` | Idempotent retry — original response recovered |

```json
{
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "aesKey": "base64-encoded-32-byte-key"
}
```

**Store the `aesKey` immediately.** The server does not persist it in plaintext — it cannot be recovered if lost (except via the idempotency window).

**Error responses:**

| Status | Cause |
|--------|-------|
| `400` | Missing / blank `message` field, malformed JSON |
| `409` | Idempotency key reused with different body |
| `413` | Body exceeds 1 MB (`app.max-message-size`) |
| `429` | Rate limit exceeded (100 requests/day/IP) |

### `POST /messages/reveal` — Reveal a secret

**Request:**

```http
POST /api/v1/messages/reveal
Content-Type: application/json

{
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "aesKey": "base64-encoded-32-byte-key"
}
```

**Success response:**

```http
HTTP/1.1 200 OK
Cache-Control: no-store

{"message": "the secret payload"}
```

The message is deleted atomically on success. Any subsequent call with the same `messageId` returns `404`.

**Error responses:**

All reveal failures return the same `404` regardless of cause (message not found, wrong key, attempts exhausted). This is intentional — distinguishing them would let attackers determine whether a message ID exists.

```json
{"error": "message not available"}
```

| Status | Cause |
|--------|-------|
| `400` | Missing / blank fields |
| `404` | Message not found, wrong key, or attempts exhausted |
| `429` | Rate limit exceeded |

**Attempt limit:** three wrong-key attempts against the same message ID delete the message permanently, regardless of which IP the attempts come from.

## NATS Interface (internal)

These subjects remain available for backend / scripted access. They are not rate-limited or exposed to the public network.

| Subject | Input | Output |
|---------|-------|--------|
| `save.msg` | plaintext string (≤ 1 MB) | `{"messageId":"...", "aeskey":"..."}` |
| `receive.msg` | `{"messageId":"...", "aeskey":"..."}` | plaintext string |

```bash
nats request save.msg "my internal secret" --server nats://localhost:4222
nats request receive.msg '{"messageId":"...","aeskey":"..."}' --server nats://localhost:4222
```

## Configuration

| Property / Env var | Default | Purpose |
|--------------------|---------|---------|
| `IDEMPOTENCY_MASTER_KEY` | dev fallback (change in prod) | Base64-encoded 32-byte AES key for encrypting AES keys in idempotency records. Generate: `openssl rand -base64 32` |
| `SPRING_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_REDIS_PORT` | `6379` | Redis port |
| `SPRING_REDIS_PASSWORD` | — | Redis password |
| `NATS_URL` | `nats://localhost:4222` | NATS broker |
| `NATS_USER` / `NATS_PASS` | — | NATS credentials |
| `app.auto-delete-days` | `2` | Message TTL in days |
| `app.max-tries` | `3` | Max failed decryption attempts before deletion |
| `app.max-message-size` | `1048576` | Max message size in bytes (1 MB) |
| `app.rate-limit.requests-per-day` | `100` | HTTP API rate limit per client IP |
| `server.forward-headers-strategy` | `NATIVE` | Trust `X-Forwarded-For` from private-range proxies |

## Security

- **AES-256-CBC** with a unique random IV per message. The server never stores the per-message key.
- **One-shot**: first successful reveal deletes the message atomically (race-safe).
- **3-strike**: three wrong-key attempts — from any IP — delete the message.
- **Rate limiting**: 100 requests/day per client IP, Redis-backed (shared across replicas).
- **Idempotency keys**: per-message AES keys stored encrypted under a server-held master key (`IDEMPOTENCY_MASTER_KEY`) inside idempotency records. Never stored in plaintext.
- **Uniform 404**: wrong key, not found, and exhausted-attempts are indistinguishable externally.
- **No caching**: all API responses carry `Cache-Control: no-store`.
- **TLS**: must be terminated at the reverse proxy (nginx, Caddy, cloud LB). The app does not serve TLS directly.

See `docs/MEMORY_HARDENING.md` for JVM/OS-level key-material protections.
See `docs/HTTP_API_DESIGN.md` for the full design rationale.
See `docs/STORAGE_NATS_VS_REDIS.md` (ADR-0001) for the Redis vs NATS JetStream architecture decision.

## Development

```bash
# Start dependencies only
docker compose up redis nats -d

# Run app locally
./gradlew bootRun

# Run with debug port
DEBUG=true docker compose up -d
```

## Testing

```bash
# All tests (requires Docker socket at /var/run/docker.sock)
./gradlew clean test

# Specific class
./gradlew test --tests MessageApiIntegrationTest

# Specific method
./gradlew test --tests "SecretMessageServiceTest.utf8RoundTripTest"
```

Tests use Testcontainers to spin up real Redis and NATS instances. The Gradle test task sets `api.version=1.44` as a JVM property to satisfy Docker Engine 25+ compatibility.

## Troubleshooting

**`IDEMPOTENCY_MASTER_KEY` missing at startup**

The application will refuse to start if the key is absent or not a valid 32-byte Base64 string. Generate one and set it in your `.env`:

```bash
openssl rand -base64 32
```

**Rate limit exceeded in dev**

The rate limit key is `ratelimit:<ip>`. Flush it in Redis:

```bash
docker compose exec redis redis-cli DEL "ratelimit:127.0.0.1"
```

**Message already retrieved / not found**

A 404 on reveal means the message is gone — either retrieved, expired, or deleted by the 3-strike counter. There is no recovery path by design.

**Connection refused to Redis or NATS**

```bash
docker compose ps        # check container status
docker compose logs redis
docker compose logs nats
```
