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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

        // Read bytes directly so Jackson handles UTF-8 decoding (getContentAsString defaults to ISO-8859-1)
        RevealResponse revealed = objectMapper.readValue(
                revealResult.getResponse().getContentAsByteArray(), RevealResponse.class);
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

        // Correct key after three wrong attempts must still return the uniform 404.
        mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), created.aesKey()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("message not available"));
    }

    @Test
    void reveal_correctKeyAfterTwoWrongAttempts_returnsPlaintext() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("survives two wrong attempts"))))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);

        String wrongKey = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/messages/reveal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RevealRequest(created.messageId(), wrongKey))))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(post("/api/v1/messages/reveal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevealRequest(created.messageId(), created.aesKey()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("survives two wrong attempts"));
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

    // ─── Infrastructure endpoints ─────────────────────────────────────────────

    @Test
    void actuatorHealth_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ─── Payload size guard ───────────────────────────────────────────────────

    @Test
    void create_payloadTooLarge_returns413() throws Exception {
        // MockHttpServletRequest.getContentLengthLong() returns the length of the actual content
        // bytes — there is no setContentLength() — so we must send a genuinely large body.
        // ~1.1 MB of 'a' characters exceeds the 1 MB app.max-message-size limit.
        String bigMessage = "a".repeat(1_100_000);
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + bigMessage + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").isString());
    }

    // ─── Idempotency edge cases ────────────────────────────────────────────────

    @Test
    void create_invalidIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "not-a-uuid")
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("invalid idempotency key"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("idempotency key must be a UUIDv4"));
    }

    @Test
    void create_blankIdempotencyKey_treatedAsAbsent_creates201NotDuplicate() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateMessageRequest("blank idempotency key test"));

        // Blank header must be treated as absent: two calls → two distinct messages (both 201)
        MvcResult first = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "   ")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "   ")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse r1 = objectMapper.readValue(first.getResponse().getContentAsString(), CreateMessageResponse.class);
        CreateMessageResponse r2 = objectMapper.readValue(second.getResponse().getContentAsString(), CreateMessageResponse.class);
        assertNotEquals(r1.messageId(), r2.messageId(),
                "Blank idempotency key must not trigger deduplication — two distinct messages must be created");
    }

    // ─── Concurrency ──────────────────────────────────────────────────────────

    @Test
    void reveal_concurrent_exactlyOneCallerSucceeds() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("concurrent reveal test"))))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);
        String revealBody = objectMapper.writeValueAsString(
                new RevealRequest(created.messageId(), created.aesKey()));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/messages/reveal")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(revealBody))
                            .andReturn();
                    statuses.add(result.getResponse().getStatus());
                } catch (Exception e) {
                    statuses.add(-1);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "All threads must finish within timeout");
        pool.shutdownNow();

        long successes = statuses.stream().filter(s -> s == 200).count();
        long notFounds = statuses.stream().filter(s -> s == 404).count();
        assertEquals(1, successes, "Exactly one reveal must succeed");
        assertEquals(threads - 1, notFounds, "All other reveals must return 404");
    }
}
