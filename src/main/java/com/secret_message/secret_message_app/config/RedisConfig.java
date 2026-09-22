package com.secret_message.secret_message_app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/**
 * The application's single Redis connection factory, and therefore its single
 * Jedis connection pool. Message storage, idempotency records and Bucket4j
 * rate-limit state all travel over it — see
 * {@link RateLimitConfig#rateLimitProxyManager}.
 *
 * <p>An explicit {@code JedisConnectionFactory} bean is required (rather than
 * Spring Boot's auto-configuration) because {@code spring-boot-starter-data-redis}
 * puts Lettuce on the classpath too, and auto-configuration prefers Lettuce
 * whenever both clients are present.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:#{null}}")
    private String redisPassword;

    /**
     * Creates the Redis connection factory, with optional password authentication.
     */
    @Bean
    public JedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }

        JedisClientConfiguration jedisClientConfiguration = JedisClientConfiguration.builder().usePooling().build();
        return new JedisConnectionFactory(config, jedisClientConfiguration);
    }
}
