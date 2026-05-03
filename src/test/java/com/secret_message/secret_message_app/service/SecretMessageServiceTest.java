package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.cache.RedisCacheManager;
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
        registry.add("nats.server.url", () -> "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222));
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

        assertNotNull(identifier.getMessageId(), "Message ID should not be null");
        assertNotNull(identifier.getSecretKey(), "Secret key should not be null");
        assertNotNull(identifier.getAeskey(), "AES key string should not be null");
        assertFalse(identifier.getMessageId().isEmpty(), "Message ID should not be empty");
        assertFalse(identifier.getAeskey().isEmpty(), "AES key string should not be empty");

        String encryptedMessage = redisCacheManager.getEncryptedMessageById(identifier.getMessageId());
        assertNotNull(encryptedMessage, "Encrypted message should be stored in Redis");
        assertFalse(encryptedMessage.isEmpty(), "Encrypted message should not be empty");

        String decryptedMessage = secretMessageService.getEncryptedMessageById(
                identifier.getMessageId(),
                identifier.getAeskey());

        assertEquals(originalMessage, decryptedMessage, "Decrypted message should match original");

        String afterDeletion = redisCacheManager.getEncryptedMessageById(identifier.getMessageId());
        assertNull(afterDeletion, "Message should be deleted from Redis after retrieval");
    }

    @Test
    void getSecretMessageWithWrongKeyTest() throws Exception {
        String originalMessage = "Secret message with wrong key test";
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(originalMessage);

        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";

        assertThrows(Exception.class,
                () -> secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey),
                "Should throw exception when using wrong key");
    }

    @Test
    void maxAttemptsTest() throws Exception {
        String originalMessage = "Message with max attempts test";
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(originalMessage);
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";

        for (int i = 0; i < 3; i++) {
            try {
                secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey);
            } catch (Exception e) {
                // expected on wrong key
            }
        }

        String result = secretMessageService.getEncryptedMessageById(identifier.getMessageId(), identifier.getAeskey());
        assertEquals("Maximum attempts reached, the message has been deleted.", result,
                "Should return max attempts message after exceeding limit");
    }
}
