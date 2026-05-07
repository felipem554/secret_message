package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import io.nats.client.Connection;
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
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Testcontainers
class NatsServiceIntegrationTest {

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
    private Connection natsConnection;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void saveAndReceiveSubjects_roundTripThenRejectSecondReceive() throws Exception {
        String secret = "NATS request reply secret";

        Message saved = natsConnection.request(
                "save.msg",
                secret.getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(5));

        assertNotNull(saved, "save.msg should respond");
        SecretMessageIdentifier identifier = objectMapper.readValue(saved.getData(), SecretMessageIdentifier.class);
        assertNotNull(identifier.getMessageId());
        assertNotNull(identifier.getAeskey());

        Message received = natsConnection.request(
                "receive.msg",
                objectMapper.writeValueAsBytes(identifier),
                Duration.ofSeconds(5));

        assertNotNull(received, "receive.msg should respond");
        String revealed = objectMapper.readValue(received.getData(), String.class);
        assertEquals(secret, revealed);

        Message secondReceive = natsConnection.request(
                "receive.msg",
                objectMapper.writeValueAsBytes(identifier),
                Duration.ofSeconds(5));

        assertNotNull(secondReceive, "second receive.msg should respond with an error");
        Map<?, ?> error = objectMapper.readValue(secondReceive.getData(), Map.class);
        assertEquals("Message not available", error.get("error"));
    }
}
