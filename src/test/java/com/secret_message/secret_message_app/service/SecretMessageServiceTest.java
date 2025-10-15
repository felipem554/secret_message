package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.cache.RedisCacheManager;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import com.secret_message.secret_message_app.utils.CrytoUtil;
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
    private CrytoUtil crytoUtil;

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

        String expectedEncryptedMessage = crytoUtil.encryptMessage(secretMessage, identifier.getSecretKey());

        // Verify the message is stored in Redis
        String encryptedMessage = redisCacheManager.getEncryptedMessageById(identifier.getMessageId());
        assertNotNull(encryptedMessage);
        assertEquals(expectedEncryptedMessage, encryptedMessage);
    }

    //TODO
    //createAndGetSecretMessageTest

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
        public CrytoUtil crytoUtil() {
            return new CrytoUtil();
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