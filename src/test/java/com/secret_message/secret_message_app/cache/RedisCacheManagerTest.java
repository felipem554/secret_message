package com.secret_message.secret_message_app.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class RedisCacheManagerTest {

    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7.0.5").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> natsContainer =
            new GenericContainer<>("nats:2.10.7-alpine").withExposedPorts(4222);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redisContainer::getHost);
        registry.add("spring.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("nats.server.url",
                () -> "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222));
    }

    @Autowired
    private RedisCacheManager redisCacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        redisTemplate.execute((org.springframework.data.redis.connection.RedisConnection conn) -> {
            conn.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void deleteIfPresent_returnsTrueOnFirstCall_falseAfter() {
        redisCacheManager.storeEncryptedMessage("race-msg", "ciphertext");

        assertTrue(redisCacheManager.deleteIfPresent("race-msg"));
        assertFalse(redisCacheManager.deleteIfPresent("race-msg"));
    }

    @Test
    void deleteIfPresent_underConcurrency_onlyOneCallerWins() throws InterruptedException {
        redisCacheManager.storeEncryptedMessage("race-msg", "ciphertext");

        int parallelism = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < parallelism; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(redisCacheManager.deleteIfPresent("race-msg"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "All workers should finish within timeout");
        long winners = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, winners, "Exactly one caller should report a successful delete");
        assertEquals(parallelism, results.size(), "All callers should report a result");
    }
}
