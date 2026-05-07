package com.secret_message.secret_message_app.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.dto.CreateMessageRequest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Rate-limit integration tests. The bucket starts fresh (dedicated Redis container)
 * and the limit is set to 3 req/day so we can exhaust it cheaply.
 * Tests are ordered so bucket consumption is predictable across the suite.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.requests-per-day=3"
)
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitIntegrationTest {

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

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(new CreateMessageRequest("rate limit test"));
    }

    // ─── Request 1 of 3: header present, remaining = 2 ───────────────────────

    @Test
    @Order(1)
    void firstRequest_setsXRateLimitRemainingHeader() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    // ─── Request 2 of 3: still allowed, remaining = 1 ────────────────────────

    @Test
    @Order(2)
    void secondRequest_stillAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
    }

    // ─── Request 3 of 3: last allowed, remaining = 0 ─────────────────────────

    @Test
    @Order(3)
    void thirdRequest_bucketExhausted_returns429WithRetryAfterHeader() throws Exception {
        // Consume the last token
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        // Now the bucket is empty — next request must be rejected
        mockMvc.perform(post("/api/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("rate limit exceeded"));
    }

    // ─── Non-API paths bypass the rate limiter ─────────────────────────────────

    @Test
    @Order(4)
    void nonApiPath_notSubjectToRateLimit() throws Exception {
        // /status is outside /api/**, rate limiter must not touch it
        mockMvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
    }
}