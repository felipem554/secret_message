package com.secret_message.secret_message_app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.dto.CreateMessageRequest;
import com.secret_message.secret_message_app.dto.CreateMessageResponse;
import com.secret_message.secret_message_app.dto.RevealRequest;
import com.secret_message.secret_message_app.dto.RevealResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MessageApiIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ─── Happy path ──────────────────────────────────────────────────────────

    @Test
    void createReveal_happyPath_returnsPlaintextThenDeletes() throws Exception {
        String secret = "Integration test secret";

        // Create
        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest(secret))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.messageId").isString())
                .andExpect(jsonPath("$.aesKey").isString())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);
        assertNotNull(created.messageId());
        assertNotNull(created.aesKey());

        // Reveal
        MvcResult revealResult = mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), created.aesKey()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        RevealResponse revealed = objectMapper.readValue(
                revealResult.getResponse().getContentAsString(), RevealResponse.class);
        assertEquals(secret, revealed.message());

        // Second reveal must return 404 — message is gone
        mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), created.aesKey()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("message not available"));
    }

    @Test
    void createReveal_nonAsciiMessage_roundTripsCorrectly() throws Exception {
        String secret = "UTF-8 test: Ação — 日本語 — 🔐";

        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest(secret))))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);

        MvcResult revealResult = mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), created.aesKey()))))
                .andExpect(status().isOk())
                .andReturn();

        RevealResponse revealed = objectMapper.readValue(
                revealResult.getResponse().getContentAsString(), RevealResponse.class);
        assertEquals(secret, revealed.message());
    }

    // ─── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void idempotency_sameKeySameBody_returnsDuplicateWithRecoveredAesKey() throws Exception {
        String idempotencyKey = java.util.UUID.randomUUID().toString();
        String secret = "Idempotency test secret";
        String body = objectMapper.writeValueAsString(new CreateMessageRequest(secret));

        // First request
        MvcResult first = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse firstResponse = objectMapper.readValue(
                first.getResponse().getContentAsString(), CreateMessageResponse.class);

        // Second request — same key + same body
        MvcResult second = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andReturn();

        CreateMessageResponse secondResponse = objectMapper.readValue(
                second.getResponse().getContentAsString(), CreateMessageResponse.class);

        assertEquals(firstResponse.messageId(), secondResponse.messageId(),
                "Retry must return the same messageId");
        assertEquals(firstResponse.aesKey(), secondResponse.aesKey(),
                "Retry must return the original AES key (recovered from MIEK-encrypted storage)");
    }

    @Test
    void idempotency_sameKeyDifferentBody_returns409() throws Exception {
        String idempotencyKey = java.util.UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("first body"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("different body"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency key conflict"));
    }

    // ─── Reveal errors ────────────────────────────────────────────────────────

    @Test
    void reveal_wrongKey_returns404_sameBodyAsNotFound() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("wrong key test"))))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);

        String wrongKey = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";

        // Wrong key → 404
        MvcResult wrongKeyResult = mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), wrongKey))))
                .andExpect(status().isNotFound())
                .andReturn();

        // Non-existent ID → same 404 body
        MvcResult notFoundResult = mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest("00000000-0000-0000-0000-000000000000", created.aesKey()))))
                .andExpect(status().isNotFound())
                .andReturn();

        assertEquals(
                wrongKeyResult.getResponse().getContentAsString(),
                notFoundResult.getResponse().getContentAsString(),
                "Wrong key and not-found must return identical response bodies");
    }

    @Test
    void reveal_threeWrongAttempts_deletesMessage_subsequentRevealReturns404() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("max attempts"))))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);

        String wrongKey = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/messages/reveal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RevealRequest(created.messageId(), wrongKey))))
                    .andExpect(status().isNotFound());
        }

        // 4th attempt with correct key — message should be deleted
        mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), created.aesKey()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("message not available"));
    }

    // ─── Input validation ─────────────────────────────────────────────────────

    @Test
    void create_blankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reveal_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"\",\"aesKey\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
