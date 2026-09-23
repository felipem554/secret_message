package com.secret_message.secret_message_app.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.serialization.Mapper;
import io.github.bucket4j.redis.jedis.Bucket4jJedis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.function.Supplier;

@Configuration
public class RateLimitConfig {

    /**
     * Small margin added to the computed TTL so a key cannot expire in the
     * same instant its last token is refilled.
     */
    private static final Duration GRACE_BEFORE_EXPIRY = Duration.ofMinutes(1);

    /**
     * Bucket4j shares the application's single Redis connection pool
     * (see {@link RedisConfig}) through {@link SpringDataRedisApi}. Using
     * Bucket4j's {@code builderFor(JedisPool)} helper instead would open a
     * second pool against the same Redis, sized independently of the first.
     *
     * <p>Without an expiration strategy Bucket4j writes {@code ratelimit:*}
     * keys with no TTL, so every client IP ever seen would stay in Redis for
     * good. The keys are expired once the bucket would have refilled to full,
     * at which point the stored state says nothing the default bucket does not
     * already say. That tracks the configured limit automatically, so raising
     * the limit for a load test does not leave the TTL behind.
     */
    @Bean
    public ProxyManager<byte[]> rateLimitProxyManager(RedisConnectionFactory redisConnectionFactory) {
        return new Bucket4jJedis.JedisBasedProxyManagerBuilder<>(
                        Mapper.BYTES, new SpringDataRedisApi(redisConnectionFactory))
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(GRACE_BEFORE_EXPIRY))
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> rateLimitBucketConfiguration(
            @Value("${app.rate-limit.requests-per-day:100}") long limit) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(limit, Duration.ofDays(1)))
                .build();
    }
}
