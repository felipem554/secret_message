# Rate-Limit Recovery: Why the Old Command Failed

## Symptom

After exceeding the HTTP rate limit (default 100 requests/day per client IP),
every `/api/*` call returns:

```json
{"error": "rate limit exceeded"}   → HTTP 429
```

The README previously documented this recovery command, which does not work:

```bash
docker compose exec redis redis-cli DEL "ratelimit:127.0.0.1"
```

It fails twice over:

```
NOAUTH Authentication required.
```

and even with authentication it would delete a key that does not exist.

## Problem 1: Redis requires authentication

The compose stack starts Redis with `--requirepass` (default password
`redispassword`, override via `REDIS_PASSWORD` in `.env`). Any `redis-cli`
command must authenticate:

```bash
docker compose exec redis redis-cli -a redispassword --no-auth-warning <command>
```

## Problem 2: The client IP is not 127.0.0.1

The rate limiter keys buckets by the **client IP the application resolves**
(`ClientIpFilter`, honoring `X-Forwarded-For` from trusted private-range
proxies). When you call the API from the host through the compose port
mapping, the connection is NATed by Docker, so the app sees the **Docker
bridge gateway address** — typically `172.18.0.1` — not `127.0.0.1`.

The actual key is therefore `ratelimit:172.18.0.1` (the exact address depends
on the Docker network; behind a real reverse proxy it is the genuine client
IP from `X-Forwarded-For`).

## Solution

List the actual rate-limit keys, then delete them:

```bash
# 1. See which buckets exist
docker compose exec redis redis-cli -a redispassword --no-auth-warning \
  --scan --pattern "ratelimit:*"

# 2. Delete the bucket for your client IP (use the key printed above)
docker compose exec redis redis-cli -a redispassword --no-auth-warning \
  DEL "ratelimit:172.18.0.1"
```

Or clear all buckets at once (dev only):

```bash
docker compose exec redis sh -c \
  'redis-cli -a redispassword --no-auth-warning --scan --pattern "ratelimit:*" \
   | xargs -r redis-cli -a redispassword --no-auth-warning DEL'
```

Buckets also expire on their own: the key TTL is 24 hours.

## How this was found

Discovered by executing every command documented in the README against a
freshly built image (2026-07-07). See `docs/RATE_LIMITING.md` for the rate
limiter design itself.
