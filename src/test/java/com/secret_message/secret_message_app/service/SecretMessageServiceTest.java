package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.cache.RedisCacheManager;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import com.secret_message.secret_message_app.utils.CryptoUtil;
import com.secret_message.secret_message_app.utils.PasswordGenerator;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.redis.host=localhost",
        "spring.redis.port=6379"
})
class SecretMessageServiceTest {

    @Container
    private static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.0.5").withExposedPorts(6379);

    @Container
    private static final GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10.7-alpine").withExposedPorts(4222);

    private static Connection natsConnection;

    @Autowired
    private NatsService natsService;

    @Autowired
    private SecretMessageService secretMessageService;

    @Autowired
    private RedisCacheManager redisCacheManager;

    @Autowired
    private CryptoUtil cryptoUtil;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void init() throws IOException, InterruptedException {
        redisContainer.start();
        natsContainer.start();

        natsConnection = Nats.connect(
                new Options.Builder().server("nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222)).build());
    }

    @AfterAll
    static void tearDownContainers() {
        natsContainer.stop();
        redisContainer.stop();
    }

    @Test
    void createSecretMessageTest() throws Exception {
        String secretMessage = "Super secret message!";
        byte[] payload = objectMapper.writeValueAsBytes(secretMessage);

        io.nats.client.Message msg = mock(io.nats.client.Message.class);
        when(msg.getData()).thenReturn(payload);
        when(msg.getReplyTo()).thenReturn("test_replyTo");
        when(msg.getSubject()).thenReturn("save.msg");

        assertDoesNotThrow(() -> natsService.createSecretMessageSubscriber(msg));

        // Create a secret message
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(secretMessage);

        // Validate the captured data
        assertNotNull(identifier.getMessageId());
        assertNotNull(identifier.getSecretKey());

        String expectedEncryptedMessage = cryptoUtil.encryptMessage(secretMessage, identifier.getSecretKey());

        // Verify the message is stored in Redis
        String encryptedMessage = redisCacheManager.getEncryptedMessageById(identifier.getMessageId());
        assertNotNull(encryptedMessage);
        assertEquals(expectedEncryptedMessage, encryptedMessage);
    }

    @Test
    void createAndGetSecretMessageTest() throws Exception {
        // Given: A secret message to encrypt
        String originalMessage = "This is a top secret message that should be encrypted!";
        
        // When: Creating a secret message
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(originalMessage);
        
        // Then: Verify the identifier is created properly
        assertNotNull(identifier.getMessageId(), "Message ID should not be null");
        assertNotNull(identifier.getSecretKey(), "Secret key should not be null");
        assertNotNull(identifier.getAeskey(), "AES key string should not be null");
        assertFalse(identifier.getMessageId().isEmpty(), "Message ID should not be empty");
        assertFalse(identifier.getAeskey().isEmpty(), "AES key string should not be empty");
        
        // And: Verify the message is stored in Redis with correct key
        String encryptedMessage = redisCacheManager.getEncryptedMessageById(identifier.getMessageId());
        assertNotNull(encryptedMessage, "Encrypted message should be stored in Redis");
        assertFalse(encryptedMessage.isEmpty(), "Encrypted message should not be empty");
        
        // When: Retrieving the secret message with the correct key
        String decryptedMessage = secretMessageService.getEncryptedMessageById(
            identifier.getMessageId(), 
            identifier.getAeskey()
        );
        
        // Then: Verify the decrypted message matches the original
        assertEquals(originalMessage, decryptedMessage, "Decrypted message should match original");
        
        // And: Verify the message is deleted after successful retrieval
        String deletedMessage = redisCacheManager.getEncryptedMessageById(identifier.getMessageId());
        assertNull(deletedMessage, "Message should be deleted from Redis after retrieval");
    }
    
    @Test
    void getSecretMessageWithWrongKeyTest() throws Exception {
        // Given: A secret message
        String originalMessage = "Secret message with wrong key test";
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(originalMessage);
        
        // When: Attempting to retrieve with a wrong key
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng=="; // Base64 encoded dummy key
        
        // Then: Should throw an exception
        assertThrows(Exception.class, () -> {
            secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey);
        }, "Should throw exception when using wrong key");
    }
    
    @Test
    void maxAttemptsTest() throws Exception {
        // Given: A secret message
        String originalMessage = "Message with max attempts test";
        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(originalMessage);
        String wrongKey = "dGhpc2lzYXdyb25na2V5MTIzNDU2Nzg5MDEyMzQ1Ng==";
        
        // When: Attempting retrieval multiple times with wrong key (max tries is 3)
        for (int i = 0; i < 3; i++) {
            try {
                secretMessageService.getEncryptedMessageById(identifier.getMessageId(), wrongKey);
            } catch (Exception e) {
                // Expected to fail with wrong key
            }
        }
        
        // Then: The message should be deleted after max attempts
        String result = secretMessageService.getEncryptedMessageById(identifier.getMessageId(), identifier.getAeskey());
        assertEquals("Maximum attempts reached, the message has been deleted.", result, 
            "Should return max attempts message after exceeding limit");
    }

    @Configuration
    static class TestConfig {

        @Bean
        public JedisConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379));
            JedisClientConfiguration jedisClientConfiguration = JedisClientConfiguration.builder().usePooling().build();
            return new JedisConnectionFactory(config, jedisClientConfiguration);
        }

        @Bean
        public StringRedisTemplate redisTemplate() {
            StringRedisTemplate template = new StringRedisTemplate();
            template.setConnectionFactory(redisConnectionFactory());
            return template;
        }

        @Bean
        public RedisCacheManager redisCacheManager(StringRedisTemplate redisTemplate) {
            return new RedisCacheManager(redisTemplate);
        }

        @Bean
        public CryptoUtil cryptoUtil() {
            return new CryptoUtil();
        }

        @Bean
        public PasswordGenerator passwordGenerator() {
            return new PasswordGenerator();
        }

        @Bean
        public SecretMessageService secretMessageService() {
            return new SecretMessageService();
        }

        @Bean
        public NatsService natsService() {
            return new NatsService(natsConnection);
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}