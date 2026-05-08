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

import static org.junit.jupiter.api.Assertions.*;

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

    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(5);
    private static final String WRONG_KEY = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    void saveMsg_happyPath_returnsMessageIdentifier() throws Exception {
        Message reply = natsConnection.request("save.msg",
                "NATS direct save test".getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);

        assertNotNull(reply, "save.msg must return a reply");
        SecretMessageIdentifier id = objectMapper.readValue(reply.getData(), SecretMessageIdentifier.class);
        assertNotNull(id.getMessageId(), "messageId must be present");
        assertNotNull(id.getAeskey(), "aeskey must be present");
    }

    @Test
    void saveMsg_thenReceiveMsg_utf8_roundTrip() throws Exception {
        String original = "NATS round-trip 🔐 — 日本語 — Ação";

        Message saveReply = natsConnection.request("save.msg",
                original.getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);
        assertNotNull(saveReply);
        SecretMessageIdentifier id = objectMapper.readValue(saveReply.getData(), SecretMessageIdentifier.class);

        Message receiveReply = natsConnection.request("receive.msg",
                objectMapper.writeValueAsBytes(id), REPLY_TIMEOUT);
        assertNotNull(receiveReply);
        String decrypted = objectMapper.readValue(receiveReply.getData(), String.class);

        assertEquals(original, decrypted, "Round-trip via NATS must recover original plaintext");
    }

    @Test
    void receiveMsg_secondReveal_returnsError() throws Exception {
        Message saveReply = natsConnection.request("save.msg",
                "one-shot message".getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);
        SecretMessageIdentifier id = objectMapper.readValue(saveReply.getData(), SecretMessageIdentifier.class);

        // First reveal succeeds
        Message first = natsConnection.request("receive.msg", objectMapper.writeValueAsBytes(id), REPLY_TIMEOUT);
        assertNotNull(first);
        String plaintext = objectMapper.readValue(first.getData(), String.class);
        assertEquals("one-shot message", plaintext);

        // Second reveal must fail — message is gone
        Message second = natsConnection.request("receive.msg", objectMapper.writeValueAsBytes(id), REPLY_TIMEOUT);
        assertNotNull(second);
        assertTrue(errorBody(second).contains("error"), "Second reveal must return an error");
    }

    // ─── Input validation ─────────────────────────────────────────────────────

    @Test
    void saveMsg_emptyPayload_returnsError() throws Exception {
        Message reply = natsConnection.request("save.msg", new byte[0], REPLY_TIMEOUT);

        assertNotNull(reply);
        assertTrue(errorBody(reply).contains("error"), "Empty payload must return an error");
    }

    @Test
    void saveMsg_whitespaceOnly_returnsError() throws Exception {
        Message reply = natsConnection.request("save.msg",
                "   ".getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);

        assertNotNull(reply);
        assertTrue(errorBody(reply).contains("error"), "Whitespace-only payload must return an error");
    }

    @Test
    void receiveMsg_invalidJson_returnsError() throws Exception {
        Message reply = natsConnection.request("receive.msg",
                "{not valid json".getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);

        assertNotNull(reply);
        assertTrue(errorBody(reply).contains("error"), "Invalid JSON must return an error");
    }

    // ─── Reveal error paths ────────────────────────────────────────────────────

    @Test
    void receiveMsg_wrongKey_returnsError() throws Exception {
        Message saveReply = natsConnection.request("save.msg",
                "wrong key nats test".getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);
        SecretMessageIdentifier id = objectMapper.readValue(saveReply.getData(), SecretMessageIdentifier.class);

        SecretMessageIdentifier wrongId = identifierWithWrongKey(id.getMessageId());
        Message reply = natsConnection.request("receive.msg",
                objectMapper.writeValueAsBytes(wrongId), REPLY_TIMEOUT);

        assertNotNull(reply);
        assertTrue(errorBody(reply).contains("error"), "Wrong key must return an error");
    }

    @Test
    void receiveMsg_nonExistentId_returnsError() throws Exception {
        SecretMessageIdentifier ghost = identifierWithWrongKey("00000000-0000-0000-0000-000000000000");
        Message reply = natsConnection.request("receive.msg",
                objectMapper.writeValueAsBytes(ghost), REPLY_TIMEOUT);

        assertNotNull(reply);
        assertTrue(errorBody(reply).contains("error"), "Non-existent ID must return an error");
    }

    @Test
    void receiveMsg_afterMaxWrongAttempts_returnsExhaustedMessage() throws Exception {
        Message saveReply = natsConnection.request("save.msg",
                "max attempts nats test".getBytes(StandardCharsets.UTF_8), REPLY_TIMEOUT);
        SecretMessageIdentifier id = objectMapper.readValue(saveReply.getData(), SecretMessageIdentifier.class);

        SecretMessageIdentifier wrongId = identifierWithWrongKey(id.getMessageId());
        for (int i = 0; i < 3; i++) {
            natsConnection.request("receive.msg", objectMapper.writeValueAsBytes(wrongId), REPLY_TIMEOUT);
        }

        // 4th attempt with correct key — counter exceeded, service throws EXHAUSTED
        Message reply = natsConnection.request("receive.msg",
                objectMapper.writeValueAsBytes(id), REPLY_TIMEOUT);
        assertNotNull(reply);

        Map<?, ?> body = objectMapper.readValue(reply.getData(), Map.class);
        assertEquals(SecretMessageService.MAX_ATTEMPTS_MESSAGE, body.get("error"),
                "Exhausted path must reply with the MAX_ATTEMPTS_MESSAGE sentinel");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private SecretMessageIdentifier identifierWithWrongKey(String messageId) {
        SecretMessageIdentifier id = new SecretMessageIdentifier();
        id.setMessageId(messageId);
        id.setAeskey(WRONG_KEY);
        return id;
    }

    private String errorBody(Message msg) {
        return new String(msg.getData(), StandardCharsets.UTF_8);
    }
}
