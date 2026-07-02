package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.cache.RedisCacheManager;
import com.secret_message.secret_message_app.exception.MessageNotAvailableException;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import com.secret_message.secret_message_app.utils.CryptoUtil;
import io.nats.client.Message;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class SecretMessageServiceTest {

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
    private NatsService natsService;

    @Autowired
    private SecretMessageService secretMessageService;

    @Autowired
    private RedisCacheManager redisCacheManager;

    @Autowired
    private CryptoUtil cryptoUtil;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSecretMessageTest() throws Exception {
        String secretMessage = "Super secret message!";

        Message msg = mock(Message.class);
        when(msg.getData()).thenReturn(secretMessage.getBytes(StandardCharsets.UTF_8));
        when(msg.getReplyTo()).thenReturn("test_replyTo");
        when(msg.getSubject()).thenReturn("save.msg");

        assertDoesNotThrow(() -> natsService.createSecretMessageSubscriber(msg));

        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(secretMessage);
        assertNotNull(identifier.getMessageId());
        assertNotNull(identifier.getSecretKey());
        assertNotNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Encrypted message should be stored in Redis");
    }

    @Test
    void createAndGetSecretMessageTest() throws Exception {
        String originalMessage = "This is a top secret message that should be encrypted!";

        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(originalMessage);
        assertNotNull(identifier.getMessageId());
        assertNotNull(identifier.getAeskey());
        assertNotNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Encrypted message should be stored in Redis");

        String decryptedMessage = secretMessageService.getEncryptedMessageById(
                identifier.getMessageId(), identifier.getAeskey());

        assertEquals(originalMessage, decryptedMessage, "Decrypted message should match original");
        assertNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Message should be deleted from Redis after retrieval");
    }

    @Test
    void createThenRevealLater_fullRoundTrip() throws Exception {
        String original = "Delayed reveal: sensitive data";

        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(original);
        assertNotNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Message should be in Redis immediately after creation");

        // Simulate time passing (well within 2-day TTL)
        Thread.sleep(200);

        String revealed = secretMessageService.getEncryptedMessageById(
                identifier.getMessageId(), identifier.getAeskey());
        assertEquals(original, revealed, "Message content must survive storage and retrieval");
        assertNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Message must be deleted after successful reveal");
    }

    @Test
    void utf8RoundTripTest() throws Exception {
        // Non-ASCII plaintext exercises the explicit UTF-8 charset fix in CryptoUtil
        String original = "Ação: Ünïcödé sëcrét — 中文 — 日本語 🔐";

        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(original);
        String revealed = secretMessageService.getEncryptedMessageById(
                identifier.getMessageId(), identifier.getAeskey());

        assertEquals(original, revealed, "Non-ASCII plaintext must survive encrypt/store/decrypt unchanged");
    }

    @Test
    void getSecretMessageWithWrongKeyTest() throws Exception {
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage("wrong key test");
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";

        assertThrows(Exception.class,
                () -> secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey),
                "Should throw when using wrong key");
    }

    @Test
    void maxAttemptsTest() throws Exception {
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage("max attempts test");
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";

        for (int i = 0; i < 2; i++) {
            MessageNotAvailableException ex = assertThrows(
                    MessageNotAvailableException.class,
                    () -> secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey));
            assertEquals(MessageNotAvailableException.Reason.WRONG_KEY, ex.getReason());
        }

        MessageNotAvailableException exhausted = assertThrows(
                MessageNotAvailableException.class,
                () -> secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey),
                "The third wrong key attempt should exhaust and delete the message");

        assertEquals(MessageNotAvailableException.Reason.EXHAUSTED, exhausted.getReason());
        assertNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Message should be deleted on the third wrong-key attempt");
    }

    @Test
    void correctKeyAfterTwoWrongAttemptsStillReveals() throws Exception {
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage("correct key still works");
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";

        for (int i = 0; i < 2; i++) {
            assertThrows(MessageNotAvailableException.class,
                    () -> secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey));
        }

        String revealed = secretMessageService.getEncryptedMessageById(
                identifier.getMessageId(), identifier.getAeskey());

        assertEquals("correct key still works", revealed);
        assertNull(redisCacheManager.getEncryptedMessageById(identifier.getMessageId()),
                "Successful reveal should delete the message");
    }

    @Test
    void attemptCounterTtlTest() throws Exception {
        // Verifies attempts:* key has TTL and does not accumulate forever
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage("ttl test");
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";

        try {
            secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey);
        } catch (Exception ignored) {}

        Long ttl = redisCacheManager.getAttemptKeyTtl(identifier.getMessageId());
        assertNotNull(ttl, "attempts:* key should exist after a failed attempt");
        assertTrue(ttl > 0, "attempts:* key must have a positive TTL (got " + ttl + ")");
    }
}
