# Rate Limiting

## Current implementation

The public HTTP API is rate limited in the application with Bucket4j backed by Redis. The filter applies to every `/api/**` request and uses one shared bucket per resolved client IP. Non-API endpoints such as `/status` and `/actuator/health` are not limited by this filter.

Default policy:

| Property | Default | Meaning |
|---|---:|---|
| `app.rate-limit.requests-per-day` | `100` | Requests allowed per client IP per rolling 24-hour window |

The rate-limit key is derived as `ratelimit:<client-ip>`. Because bucket state is stored in Redis, multiple app replicas share the same limit.

## Request flow

1. `ClientIpFilter` resolves the client IP from `request.getRemoteAddr()`. With `server.forward-headers-strategy=NATIVE`, Tomcat's trusted proxy handling can replace this with a trusted `X-Forwarded-For` value.
2. `RateLimitFilter` looks up the Redis-backed Bucket4j bucket for that IP.
3. If a token is available, the request continues and the response includes `X-RateLimit-Remaining`.
4. If the bucket is empty, the filter returns `429 Too Many Requests` immediately.

Rejected response shape:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: <seconds>
Cache-Control: no-store
Content-Type: application/json

{"error":"rate limit exceeded"}
```

## Configuration

Application properties:

```properties
app.rate-limit.requests-per-day=100
```

Redis connection settings are shared with the rest of the app:

```properties
spring.redis.host=${SPRING_REDIS_HOST:localhost}
spring.redis.port=${SPRING_REDIS_PORT:6379}
spring.redis.password=${SPRING_REDIS_PASSWORD:}
```

## Operational notes

- Keep edge or API-gateway rate limiting in front of this service for public deployments. The application limiter is defense in depth and handles replica-safe per-IP limits.
- Make sure the app is not reachable directly from the internet if you rely on proxy-provided `X-Forwarded-For`; otherwise clients can collapse into the proxy IP or bypass intended edge controls.
- For local testing, clear a bucket with `redis-cli DEL "ratelimit:127.0.0.1"` or flush the test Redis database.
- The NATS interface is internal-only and is not covered by the HTTP rate-limit filter. Restrict NATS network access to trusted backend services.

## Tests

`RateLimitIntegrationTest` starts a dedicated Redis container and lowers `app.rate-limit.requests-per-day` to `3` so the suite can verify allowed requests, `X-RateLimit-Remaining`, `429`, and `Retry-After` behavior cheaply.
