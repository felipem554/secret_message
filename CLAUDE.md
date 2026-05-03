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
curl http://localhost:8080/status
```

## Tests

```bash
# All tests (uses Testcontainers — requires Docker socket at /var/run/docker.sock)
./gradlew test

# Single test class
./gradlew test --tests SecretMessageServiceTest

# Specific test method
./gradlew test --tests "SecretMessageServiceTest.createAndGetSecretMessageTest"
```

Tests use Testcontainers to spin up real Redis and NATS instances via `@DynamicPropertySource`. The Gradle test task passes `api.version=1.44` as a JVM system property to satisfy Docker Engine 25+ which dropped support for API versions below 1.40.

## Architecture

**Spring Boot 3** application that exposes a secret-message service over **NATS** (not HTTP). Clients publish to NATS subjects; Redis stores encrypted messages with a TTL.

### Request flow

```
Client → NATS save.msg (plaintext)
  → NatsService → SecretMessageService
  → AES-256 encrypt (random key + IV) → Redis (TTL: 2 days)
  ← {messageId, aeskey}

Client → NATS receive.msg ({messageId, aeskey})
  → SecretMessageService
  → Redis fetch → AES-256 decrypt → Redis delete
  ← plaintext message
```

### NATS subjects

| Subject | Input | Output |
|---|---|---|
| `save.msg` | plaintext string | `{"messageId":"...", "aeskey":"..."}` |
| `receive.msg` | `{"messageId":"...", "aeskey":"..."}` | plaintext string |

### Key classes

- **`NatsService`** — subscribes to `save.msg` / `receive.msg`, validates input (max 1 MB), delegates to `SecretMessageService`. Started on `ApplicationReadyEvent`.
- **`SecretMessageService`** — orchestrates encryption/decryption; enforces max-attempts (default 3) — message auto-deletes after 3 failed decryption attempts.
- **`RedisCacheManager`** — stores `messages:<id>` (encrypted payload) and `attempts:<id>` (failure counter) with TTL.
- **`CryptoUtil`** — AES-256-CBC with PKCS5 padding; random IV prepended to ciphertext.

### Security model

- Each message gets a unique random AES-256 key and IV.
- The service **never stores the decryption key** — only the client holds it.
- Message self-destructs on first successful read.
- 3 failed decryption attempts trigger deletion.

## Configuration

Key properties in `application.properties` / environment:

| Property / Env var | Default | Purpose |
|---|---|---|
| `NATS_URL` | `nats://localhost:4222` | NATS broker URL |
| `NATS_USER` / `NATS_PASS` | — | NATS auth (optional) |
| `spring.redis.host/port/password` | localhost:6379 | Redis connection |
| `app.auto-delete-days` | `2` | Message TTL in days |
| `app.max-tries` | `3` | Max failed decryption attempts |
| `app.max-message-size` | `1048576` | Max message size in bytes (1 MB) |

Copy `.env` for Docker Compose secrets; `application.properties` for local development.

## Ports

| Service | Port |
|---|---|
| Spring Boot HTTP | 8080 |
| JVM debug (when `DEBUG=true`) | 5005 |
| Redis | 6379 |
| NATS clients | 4222 |
| NATS monitoring | 8222 |

## Rate limiting

Rate limiting is **documented but not yet implemented** — see `docs/RATE_LIMITING.md` for the recommended approaches (API Gateway, Spring Boot Bucket4j, or NATS-side).
