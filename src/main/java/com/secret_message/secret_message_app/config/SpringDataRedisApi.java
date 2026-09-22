package com.secret_message.secret_message_app.config;

import io.github.bucket4j.redis.jedis.RedisApi;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;

/**
 * Bridges Bucket4j's {@link RedisApi} onto the application's Spring Data
 * connection factory, so rate-limit state travels over the same Jedis pool as
 * message and idempotency storage instead of a second, independently sized one.
 *
 * <p>Bucket4j's own {@code builderFor(JedisPool)} helpers each open their own
 * pool, which is why this three-method SPI is implemented here instead.
 */
class SpringDataRedisApi implements RedisApi {

    private final RedisConnectionFactory connectionFactory;

    SpringDataRedisApi(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Bucket4j's compare-and-swap compares the script result against
     * {@code 0L}, so the Lua integer must come back as a {@code Long} —
     * that is what {@link ReturnType#INTEGER} maps it to.
     */
    @Override
    public Object eval(byte[] script, int keyCount, byte[]... params) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.scriptingCommands().eval(script, ReturnType.INTEGER, keyCount, params);
        }
    }

    @Override
    public byte[] get(byte[] key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.stringCommands().get(key);
        }
    }

    @Override
    public void delete(byte[] key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().del(key);
        }
    }
}
