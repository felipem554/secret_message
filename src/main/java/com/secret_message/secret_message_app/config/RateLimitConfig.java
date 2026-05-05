package com.secret_message.secret_message_app.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.jedis.cas.JedisBasedProxyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.function.Supplier;

@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "close")
    public JedisPool rateLimitJedisPool(
            @Value("${spring.redis.host}") String host,
            @Value("${spring.redis.port}") int port,
            @Value("${spring.redis.password:#{null}}") String password) {

        if (password != null && !password.isEmpty()) {
            return new JedisPool(new JedisPoolConfig(), host, port, 2000, password);
        }
        return new JedisPool(new JedisPoolConfig(), host, port);
    }

    @Bean
    public ProxyManager<byte[]> rateLimitProxyManager(JedisPool rateLimitJedisPool) {
        return JedisBasedProxyManager.builderFor(rateLimitJedisPool).build();
    }

    @Bean
    public Supplier<BucketConfiguration> rateLimitBucketConfiguration(
            @Value("${app.rate-limit.requests-per-day:100}") long limit) {

        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(limit, Duration.ofDays(1)))
                .build();
    }
}
