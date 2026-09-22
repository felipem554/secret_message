package com.secret_message.secret_message_app.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
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
     * Bucket4j shares the application's single Redis connection pool
     * (see {@link RedisConfig}) through {@link SpringDataRedisApi}. Using
     * Bucket4j's {@code builderFor(JedisPool)} helper instead would open a
     * second pool against the same Redis, sized independently of the first.
     */
    @Bean
    public ProxyManager<byte[]> rateLimitProxyManager(RedisConnectionFactory redisConnectionFactory) {
        return new Bucket4jJedis.JedisBasedProxyManagerBuilder<>(
                Mapper.BYTES, new SpringDataRedisApi(redisConnectionFactory)).build();
    }

    @Bean
    public Supplier<BucketConfiguration> rateLimitBucketConfiguration(
            @Value("${app.rate-limit.requests-per-day:100}") long limit) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(limit, Duration.ofDays(1)))
                .build();
    }
}
