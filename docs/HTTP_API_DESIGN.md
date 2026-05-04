# HTTP API — Design Plan

**Status:** draft, pending review.
**Scope:** add a public HTTP API on top of `SecretMessageService`. NATS becomes internal-only.

---

## 1. Goals and non-goals

**Goals:**

- Expose a public, REST-shaped interface for creating and revealing one-shot secrets.
- Preserve the existing security guarantees: server never persists the per-message AES key in plaintext; messages self-destruct on first read; failed-decryption attempts capped at 3 per message.
- Make the service safely operable behind a standard reverse proxy (TLS termination, request logging, rate limiting at the edge).

**Non-goals:**

- Replacing NATS. NATS continues to serve internal/system traffic against the same `SecretMessageService`.
- Multi-tenancy or user accounts.
- Client-side UI. The fragment-URL pattern (key in URL fragment, served by a JS reveal page) is a follow-up.

---

## 2. API surface

| Method | Path | Auth | Body | Headers | Success | TTL behavior |
|---|---|---|---|---|---|---|
| `POST` | `/api/v1/messages` | API key (future) | `{"message": "..."}` | `Idempotency-Key: <uuid>` (optional) | `201 {"messageId": "...", "aesKey": "..."}` | Message + idempotency record both expire at `app.auto-delete-days` |
| `POST` | `/api/v1/messages/reveal` | none | `{"messageId": "...", "aesKey": "..."}` | — | `200 {"message": "..."}` then deletes | Message deleted on success or after 3 wrong attempts |
| `GET` | `/actuator/health` | none | — | — | `200 {"status": "UP", ...}` | — |
| `GET` | `/actuator/metrics/...` | restricted to internal IPs | — | — | metrics payload | — |

**Design rules in effect:**

- Message ID is never in the request URI or response headers — only in JSON bodies.
- AES key never in the URI; in request body for reveal, in response body for create.
- Both endpoints are `POST` (reveal is destructive, would be unsafe as `GET`).
- Versioned under `/api/v1/`.

---

## 3. Request / response specs

### `POST /api/v1/messages`

**Request:**

```http
POST /api/v1/messages HTTP/1.1
Content-Type: application/json
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

{"message": "the secret payload"}
```

**Validation:**

- `message` required, non-empty string.
- Total request body ≤ 1 MB (re-enforces existing `app.max-message-size`).
- `Idempotency-Key`, if present, must be a valid UUIDv4.

**Success response:**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Cache-Control: no-store

{
  "messageId": "<uuid>",
  "aesKey": "<base64-encoded-32-byte-key>"
}
```

**Idempotent retry response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{
  "messageId": "<uuid>",
  "aesKey": "<base64-encoded-32-byte-key>",
  "duplicate": true
}
```

Note `200` vs `201` — a duplicate response signals "already processed," not "newly created."

### `POST /api/v1/messages/reveal`

**Request:**

```http
POST /api/v1/messages/reveal HTTP/1.1
Content-Type: application/json

{
  "messageId": "<uuid>",
  "aesKey": "<base64-encoded-32-byte-key>"
}
```

**Success response:**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"message": "the secret payload"}
```

After a successful response is flushed to the socket, the message and its `attempts:*` counter are deleted from Redis.

---

## 4. Error model

### Non-leaky errors — proper HTTP semantics

| Endpoint | Condition | Code | Body |
|---|---|---|---|
| both | Malformed JSON / missing required field | `400` | `{"error": "invalid request", "details": "..."}` |
| `POST /messages` | Request body > 1 MB | `413` | `{"error": "payload too large"}` |
| `POST /messages` | `Idempotency-Key` reused with different body | `409` | `{"error": "idempotency key conflict"}` |
| both | Rate limit exceeded | `429` (with `Retry-After`) | `{"error": "rate limit exceeded"}` |
| both | Auth missing/invalid (when API key is added) | `401` | `{"error": "unauthorized"}` |
| both | Redis or backend unavailable | `503` | `{"error": "service unavailable"}` |
| both | Unexpected server error | `500` | `{"error": "internal error"}` (no stack trace exposed) |

### Reveal endpoint — uniform 404 for message-state failures

| Internal condition | External code | External body |
|---|---|---|
| Message ID does not exist | `404` | `{"error": "message not available"}` |
| Wrong AES key | `404` | `{"error": "message not available"}` |
| Attempts exhausted (3 wrong tries → message deleted) | `404` | `{"error": "message not available"}` |

**Why uniform:** distinguishing these codes lets attackers enumerate which IDs exist and probe the attempt counter without burning real attempts. The 3-strike security control depends on the attacker not knowing which case they hit.

**Internal observability:** Micrometer counters increment separately on each case (`reveal.failed.not_found`, `reveal.failed.wrong_key`, `reveal.failed.exhausted`) so operators see the breakdown via `/actuator/metrics`.

---

## 5. Authentication

**Phase 1 (this PR):** No authentication on either endpoint. The service is meant to be reachable behind a reverse proxy that enforces TLS and rate limiting. Document that running it open to the public internet without auth is not the intended deployment.

**Phase 2 (follow-up):** Static API key on `POST /messages` only. `Authorization: Bearer <key>`. Reveal stays open because the AES key in the request body *is* the authorization. API key validates via constant-time compare against a value loaded from `.env` (`API_KEY`). Rotation by env-var change + restart for the first version; rotation without downtime is a Phase 3 concern.

---

## 6. Rate limiting

**Choice:** Bucket4j with `Bandwidth.simple(100, Duration.ofDays(1))` per IP, single bucket shared across both endpoints.

**Storage:** Bucket4j's Redis (Jedis) adapter. Reuses the existing Redis connection, no new infrastructure.

**Key derivation:** `ratelimit:<ip>` where `<ip>` is derived from `X-Forwarded-For` (see §7).

**Response on exceed:**

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 86400
Content-Type: application/json

{"error": "rate limit exceeded"}
```

**Implementation point:** Bucket4j filter must run before the controller and after the IP-resolution filter. Order: TLS termination (proxy) → IP resolution filter → rate limit filter → idempotency filter → controller.

**Out of scope for Phase 1:** per-message-ID reveal rate limit. Per-message brute force is already capped at 3 attempts globally by the existing counter; per-IP limiting handles enumeration attacks.

---

## 7. Client IP resolution

**Trust model:** the application is unreachable directly from the internet. All public traffic goes through a reverse proxy (nginx, Caddy, cloud LB, or Cloudflare) which sets `X-Forwarded-For`.

**Spring configuration:**

```properties
server.forward-headers-strategy=NATIVE
server.tomcat.remoteip.internal-proxies=10\\.\\d+\\.\\d+\\.\\d+|127\\.0\\.0\\.1|172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d+\\.\\d+
```

The regex restricts XFF trust to RFC 1918 private ranges. XFF arriving from a public IP is ignored (cannot be spoofed by an internet client).

**Filter behavior:**

- Production profile: if `request.getRemoteAddr()` returns null, `0.0.0.0`, or a known proxy IP after RemoteIpValve processing, return `400` immediately. This catches misconfiguration where the proxy is bypassed.
- Dev profile (`./gradlew bootRun`): allow `127.0.0.1` to act as both the proxy and the client, so local testing works without setting up a proxy.

---

## 8. Idempotency — encrypted-key storage

**Decision:** store the per-message AES key encrypted with a server-held master key, so retries after a network drop can recover the original key.

**Master key (MIEK — Master Idempotency Encryption Key):**

- 32 random bytes, Base64-encoded in `.env` as `IDEMPOTENCY_MASTER_KEY`.
- Loaded once at startup into a `byte[]` field on a singleton `IdempotencyKeyVault` bean.
- Never logged, never exposed via Actuator endpoints.
- Subject to the same memory hardening rules as per-message keys (see `docs/MEMORY_HARDENING.md`).
- Generation: `openssl rand -base64 32` documented in README.

**Encryption scheme:** AES-256-CBC with PKCS5 padding, matching the existing per-message encryption in `CryptoUtil`.

- Random 16-byte IV per record, prepended to the ciphertext (`iv || ciphertext`).
- Reuses the existing CBC code path. No new cipher mode is introduced; `CryptoUtil` is refactored only to expose a `byte[]`-keyed variant of its existing methods.
- **Trade-off accepted:** CBC provides confidentiality but not authenticity. An attacker with write access to Redis could tamper with `idempotency:<uuid>.encrypted_aes_key`. On retry, the server would decrypt to garbage bytes and return them as the AES key; the client then burns 3 reveal attempts using that garbage key, after which the existing 3-strike counter deletes the message. Net effect: tampering with idempotency records → silent denial-of-message. Mitigated operationally by binding Redis to the private network and never exposing it to untrusted clients. Authenticated encryption (AES-GCM) is a viable future upgrade and would not break on-disk format compatibility because idempotency records expire within `app.auto-delete-days`.

**Redis schema:**

```
Key:   idempotency:<uuid>
Value: JSON {
  "body_hash": "<sha256-hex>",
  "message_id": "<uuid>",
  "encrypted_aes_key": "<base64(iv || ciphertext)>",
  "created_at": <epoch-millis>
}
TTL:   same as the message itself (app.auto-delete-days * 86400 seconds)
```

**Flow on `POST /messages` with `Idempotency-Key`:**

```
1. Compute body_hash = SHA-256(raw request body).
2. GET idempotency:<uuid>.
   a. If exists and stored.body_hash == body_hash:
        → decrypt stored.encrypted_aes_key with MIEK
        → return 200 {messageId, aesKey, duplicate: true}
   b. If exists and stored.body_hash != body_hash:
        → return 409 (do not leak the previous messageId)
3. If not exists:
   → process create normally
   → encrypt the new AES key with MIEK
   → SET idempotency:<uuid> = {body_hash, messageId, encrypted_aes_key, created_at}
     with NX flag and TTL
   → return 201 {messageId, aesKey}
```

**Threats and mitigations:**

| Threat | Mitigation |
|---|---|
| MIEK leaked from `.env` | All idempotency records become decryptable. File perms 600, app user only, never in container image, rotation procedure documented. |
| Redis dump leaks ciphertext | Without MIEK, ciphertext is opaque. Defense-in-depth via at-rest encryption on Redis if available. |
| MIEK in JVM heap dump | Covered by `MEMORY_HARDENING.md` controls. MIEK lives in a single `byte[]` field, never copied into `String`. |
| **Tampering with `encrypted_aes_key` in Redis** | **Not detected (CBC has no authenticity).** Decrypts to garbage; client burns 3 reveal attempts; message is then deleted by the 3-strike counter. Mitigated by Redis network isolation; future upgrade to AES-GCM would close this. |
| Replay with stolen `Idempotency-Key` | Body hash mismatch returns 409. Replay with the *same* body returns the same messageId — which is the intended behavior. |
| Idempotency record outliving the message | Both expire at the same TTL; if `app.auto-delete-days` changes, both honor the new value. |

**Rotation (out of scope for Phase 1, documented for later):** Support `IDEMPOTENCY_MASTER_KEY` (current) and `IDEMPOTENCY_MASTER_KEY_PREVIOUS` (decrypt-only). Rotation procedure: set `_PREVIOUS` to current value, generate new `IDEMPOTENCY_MASTER_KEY`, restart, wait `auto-delete-days`, remove `_PREVIOUS`.

---

## 9. Storage schema summary

| Key | Type | Value | TTL | Atomicity guarantee |
|---|---|---|---|---|
| `messages:<id>` | string | encrypted payload | `auto-delete-days` | `GET` + `DEL` in MULTI/EXEC for atomic delete-after-read |
| `attempts:<id>` | counter | wrong-key attempt count | inherits `auto-delete-days` on first INCR | `INCR` is atomic |
| `idempotency:<uuid>` | string (JSON) | `{body_hash, messageId, encrypted_aes_key, created_at}` | matches message TTL | `SET ... NX EX` for atomic create-if-absent |
| `ratelimit:<ip>:*` | bucket state | Bucket4j internal | 24h | Bucket4j handles atomicity via Lua script |

---

## 10. Configuration additions

`application.properties`:

```properties
# HTTP API
server.port=8080
server.forward-headers-strategy=NATIVE
server.tomcat.remoteip.internal-proxies=10\\.\\d+\\.\\d+\\.\\d+|127\\.0\\.0\\.1|172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d+\\.\\d+

# Rate limiting
app.rate-limit.requests-per-day=100

# Idempotency
app.idempotency.master-key=${IDEMPOTENCY_MASTER_KEY}
```

`.env` (added, never committed):

```
IDEMPOTENCY_MASTER_KEY=<output of: openssl rand -base64 32>
```

`docker-compose.yml`: pass `IDEMPOTENCY_MASTER_KEY` through to the app container, drop unnecessary capabilities on the app service.

---

## 11. Code changes

**New classes:**

- `web.controller.MessageController` — thin adapter over `SecretMessageService`. Two endpoints from §2.
- `web.dto.CreateMessageRequest`, `CreateMessageResponse`, `RevealRequest`, `RevealResponse`, `ErrorResponse` — request/response DTOs.
- `web.exception.GlobalExceptionHandler` — `@RestControllerAdvice` mapping internal exceptions to the error model in §4.
- `web.filter.ClientIpFilter` — resolves trusted client IP from `X-Forwarded-For`, rejects misconfigured requests.
- `web.filter.RateLimitFilter` — Bucket4j integration.
- `web.filter.IdempotencyFilter` — handles the create-side idempotency lookup before the controller runs.
- `idempotency.IdempotencyKeyVault` — holds the MIEK, exposes `encrypt(byte[])` / `decrypt(byte[])`.
- `idempotency.IdempotencyService` — Redis read/write of idempotency records.

**Modified classes:**

- `SecretMessageService` — minor: ensure `createSecretMessage` and `getEncryptedMessageById` have signatures usable by both `NatsService` and `MessageController` without duplicating validation.
- `NatsService` — no behavioral change; refactor any validation that should live in `SecretMessageService` so the new controller doesn't duplicate it.
- `RedisCacheManager` — add atomic `getAndDelete(messageId)` (Lua script or MULTI/EXEC) to replace the current two-step read-then-delete. This closes the race condition for both transports.
- `CryptoUtil` — refactor to expose `encrypt(byte[] plaintext, byte[] key) -> byte[]` and `decrypt(byte[] ciphertext, byte[] key) -> byte[]` so MIEK can reuse the same CBC implementation as per-message. No mode change. Existing `String`-based methods become thin wrappers around the new byte-array methods.

**No changes to:**

- The encryption format on disk for existing messages (CBC). MIEK encryption is a separate path.
- The 3-strike attempt counter logic.
- The `auto-delete-days` mechanism.

---

## 12. Test plan

**Unit:**

- `IdempotencyKeyVault` round-trip (encrypt / decrypt with same key, decrypt fails with wrong key, decrypt fails on tampered ciphertext).
- `RedisCacheManager.getAndDelete` atomicity under simulated concurrent calls.
- `ClientIpFilter` honors XFF from trusted ranges, rejects from public IPs in production profile, falls back in dev profile.

**Integration (Testcontainers, extending the current suite):**

- `POST /messages` happy path → `POST /messages/reveal` happy path → second reveal returns 404.
- `POST /messages` with `Idempotency-Key`, retry with same body → 200 + `duplicate: true` + same `messageId` and `aesKey`.
- `POST /messages` with `Idempotency-Key`, retry with different body → 409.
- 3 wrong-key reveals → message deleted; 4th call with correct key → 404.
- Rate limit: 100 requests succeed, 101st returns 429.
- Reveal returns uniform 404 for not-found, wrong-key, exhausted; metrics counters increment correctly.
- 1 MB + 1 byte payload → 413.

**Manual:**

- TLS terminated nginx in front of the app, confirm XFF is honored and rate limiting works per real client IP.
- Heap dump after a create request, `grep` for the AES key value — should not appear after the response is flushed (validates §8 + `MEMORY_HARDENING.md`).

---

## 13. Out of scope for this PR

- API key authentication on `POST /messages` (Phase 2).
- MIEK rotation procedure (Phase 2).
- Fragment-URL web client.
- Implementation of the verification scripts in `MEMORY_HARDENING.md` §Verification.
- OpenAPI / Swagger generation.

---

## 14. Review checklist

Before approving this plan, please confirm:

- [ ] §2 API surface matches what you want clients to see.
- [ ] §4 error model — uniform 404 on reveal acceptable, distinct codes elsewhere acceptable.
- [ ] §5 — shipping Phase 1 with no auth on create is OK because the service is meant to be private behind a proxy.
- [ ] §8 — encrypted-key idempotency understood (MIEK in `.env`, recoverable on retry, master-key-leak is the new failure mode).
- [ ] §11 — class layout is acceptable; nothing to be renamed or merged.
- [ ] §13 — out-of-scope items can wait.

Once approved, the implementation will be split into discrete commits matching §11.
