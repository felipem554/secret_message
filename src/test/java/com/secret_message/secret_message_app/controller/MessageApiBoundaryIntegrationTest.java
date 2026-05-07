package com.secret_message.secret_message_app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.dto.CreateMessageRequest;
import com.secret_message.secret_message_app.dto.CreateMessageResponse;
import com.secret_message.secret_message_app.dto.RevealRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.max-message-size=32")
@AutoConfigureMockMvc
@Testcontainers
class MessageApiBoundaryIntegrationTest {

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

    @Test
    void create_payloadOverConfiguredLimit_returns413NoStore() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateMessageRequest("this payload is definitely over the configured limit"))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("payload too large"));
    }

    @Test
    void concurrentReveal_sameMessage_onlyOneCallerReceivesPlaintext() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMessageRequest("race reveal"))))
                .andExpect(status().isCreated())
                .andReturn();

        CreateMessageResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), CreateMessageResponse.class);

        int parallelism = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < parallelism; i++) {
            tasks.add(() -> mockMvc.perform(post("/api/v1/messages/reveal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RevealRequest(created.messageId(), created.aesKey()))))
                    .andReturn()
                    .getResponse()
                    .getStatus());
        }

        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdownNow();

        long successes = 0;
        long notFound = 0;
        for (Future<Integer> future : futures) {
            int status = future.get();
            if (status == 200) {
                successes++;
            } else if (status == 404) {
                notFound++;
            }
        }

        assertEquals(1, successes, "Only one concurrent reveal can consume the message");
        assertEquals(parallelism - 1, notFound, "All losing reveal attempts should receive uniform 404");
    }
}
