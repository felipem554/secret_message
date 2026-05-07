package com.secret_message.secret_message_app.filter;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Per-IP rate limiter for {@code /api/**}.
 *
 * <p>One bucket per resolved client IP, shared across all API endpoints.
 * Backed by Redis via Bucket4j so multiple application replicas share state.
 * Default limit: 100 requests / 24-hour rolling window
 * (configurable via {@code app.rate-limit.requests-per-day}).
 *
 * <p>On exceed: returns HTTP 429 with a {@code Retry-After} header
 * (in seconds, derived from the bucket's nano-precision refill estimate).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/api/";

    private final ProxyManager<byte[]> rateLimitProxyManager;
    private final Supplier<BucketConfiguration> rateLimitBucketConfiguration;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!request.getRequestURI().startsWith(PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = (String) request.getAttribute(ClientIpFilter.CLIENT_IP_ATTRIBUTE);
        if (clientIp == null) {
            clientIp = request.getRemoteAddr();
        }

        byte[] bucketKey = ("ratelimit:" + clientIp).getBytes(StandardCharsets.UTF_8);
        Bucket bucket = rateLimitProxyManager.builder().build(bucketKey, rateLimitBucketConfiguration);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
    }
}
