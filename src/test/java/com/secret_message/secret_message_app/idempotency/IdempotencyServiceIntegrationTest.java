package com.secret_message.secret_message_app.idempotency;

import com.secret_message.secret_message_app.exception.IdempotencyConflictException;
import com.secret_message.secret_message_app.utils.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class IdempotencyServiceIntegrationTest {

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
    private IdempotencyService idempotencyService;

    @Autowired
    private CryptoUtil cryptoUtil;

    // ─── Store + find ─────────────────────────────────────────────────────────

    @Test
    void store_findExisting_roundTrip() throws Exception {
        String iKey = UUID.randomUUID().toString();
        String body = "{\"message\":\"hello\"}";
        String bodyHash = idempotencyService.hashBody(body);
        String messageId = UUID.randomUUID().toString();
        String aesKeyBase64 = freshAesKeyBase64();

        assertTrue(idempotencyService.store(iKey, bodyHash, messageId, aesKeyBase64));
        Optional<IdempotencyRecord> found = idempotencyService.findExisting(iKey, bodyHash);

        assertTrue(found.isPresent(), "Record should be found after store");
        assertEquals(messageId, found.get().messageId());
        assertEquals(bodyHash, found.get().bodyHash());
    }

    @Test
    void findExisting_unknownKey_returnsEmpty() {
        Optional<IdempotencyRecord> result = idempotencyService.findExisting(
                UUID.randomUUID().toString(), "any-hash");

        assertTrue(result.isEmpty(), "Unknown idempotency key must return empty");
    }

    @Test
    void findExisting_sameKeyDifferentBodyHash_throwsConflict() throws Exception {
        String iKey = UUID.randomUUID().toString();
        String originalBody = "{\"message\":\"first\"}";
        String originalHash = idempotencyService.hashBody(originalBody);

        assertTrue(idempotencyService.store(iKey, originalHash, UUID.randomUUID().toString(), freshAesKeyBase64()));

        String differentHash = idempotencyService.hashBody("{\"message\":\"different\"}");
        assertThrows(IdempotencyConflictException.class,
                () -> idempotencyService.findExisting(iKey, differentHash),
                "Reusing a key with a different body must throw IdempotencyConflictException");
    }

    // ─── AES key recovery ─────────────────────────────────────────────────────

    @Test
    void recoverAesKey_returnsOriginalKey() throws Exception {
        String iKey = UUID.randomUUID().toString();
        String body = "{\"message\":\"key recovery test\"}";
        String bodyHash = idempotencyService.hashBody(body);
        String messageId = UUID.randomUUID().toString();
        String originalAesKeyBase64 = freshAesKeyBase64();

        assertTrue(idempotencyService.store(iKey, bodyHash, messageId, originalAesKeyBase64));
        IdempotencyRecord record = idempotencyService.findExisting(iKey, bodyHash).orElseThrow();
        String recovered = idempotencyService.recoverAesKey(record);

        assertEquals(originalAesKeyBase64, recovered,
                "recoverAesKey must return the exact key that was stored");
    }

    // ─── hashBody ─────────────────────────────────────────────────────────────

    @Test
    void hashBody_deterministicAndSensitiveToInput() {
        String body = "{\"message\":\"hello\"}";

        assertEquals(idempotencyService.hashBody(body), idempotencyService.hashBody(body),
                "Same input must always produce the same hash");
        assertNotEquals(idempotencyService.hashBody(body),
                idempotencyService.hashBody("{\"message\":\"different\"}"),
                "Different inputs must produce different hashes");
    }

    // ─── setIfAbsent semantics ─────────────────────────────────────────────────

    @Test
    void store_secondCallForSameKey_doesNotOverwriteFirstRecord() throws Exception {
        String iKey = UUID.randomUUID().toString();
        String body = "{\"message\":\"idempotent store\"}";
        String bodyHash = idempotencyService.hashBody(body);
        String firstMessageId  = UUID.randomUUID().toString();
        String secondMessageId = UUID.randomUUID().toString();

        assertTrue(idempotencyService.store(iKey, bodyHash, firstMessageId, freshAesKeyBase64()));
        assertFalse(idempotencyService.store(iKey, bodyHash, secondMessageId, freshAesKeyBase64()));

        IdempotencyRecord found = idempotencyService.findExisting(iKey, bodyHash).orElseThrow();
        assertEquals(firstMessageId, found.messageId(),
                "Second store must not overwrite the first record (setIfAbsent semantics)");
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private String freshAesKeyBase64() throws Exception {
        SecretKey key = cryptoUtil.generateRandomAESKey();
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
