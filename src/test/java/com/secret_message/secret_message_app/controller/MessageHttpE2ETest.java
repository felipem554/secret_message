package com.secret_message.secret_message_app.controller;

import com.secret_message.secret_message_app.dto.CreateMessageRequest;
import com.secret_message.secret_message_app.dto.CreateMessageResponse;
import com.secret_message.secret_message_app.dto.ErrorResponse;
import com.secret_message.secret_message_app.dto.RevealRequest;
import com.secret_message.secret_message_app.dto.RevealResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MessageHttpE2ETest {

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
    private TestRestTemplate restTemplate;

    @Test
    void createRevealOverRealHttpPort_deletesMessageAfterFirstReveal() {
        ResponseEntity<CreateMessageResponse> create = restTemplate.postForEntity(
                "/api/v1/messages",
                new CreateMessageRequest("real HTTP end-to-end secret"),
                CreateMessageResponse.class);

        assertEquals(HttpStatus.CREATED, create.getStatusCode());
        assertEquals("no-store", create.getHeaders().getCacheControl());
        assertNotNull(create.getBody());
        assertNotNull(create.getBody().messageId());
        assertNotNull(create.getBody().aesKey());
        assertNull(create.getBody().duplicate());

        ResponseEntity<RevealResponse> reveal = restTemplate.postForEntity(
                "/api/v1/messages/reveal",
                new RevealRequest(create.getBody().messageId(), create.getBody().aesKey()),
                RevealResponse.class);

        assertEquals(HttpStatus.OK, reveal.getStatusCode());
        assertEquals("real HTTP end-to-end secret", reveal.getBody().message());

        ResponseEntity<ErrorResponse> secondReveal = restTemplate.postForEntity(
                "/api/v1/messages/reveal",
                new RevealRequest(create.getBody().messageId(), create.getBody().aesKey()),
                ErrorResponse.class);

        assertEquals(HttpStatus.NOT_FOUND, secondReveal.getStatusCode());
        assertEquals("message not available", secondReveal.getBody().error());
    }

    @Test
    void idempotentRetryOverRealHttpPort_returnsOriginalIdentifierAndKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());

        HttpEntity<CreateMessageRequest> request = new HttpEntity<>(
                new CreateMessageRequest("retry-safe create"), headers);

        ResponseEntity<CreateMessageResponse> first = restTemplate.exchange(
                "/api/v1/messages", HttpMethod.POST, request, CreateMessageResponse.class);
        ResponseEntity<CreateMessageResponse> retry = restTemplate.exchange(
                "/api/v1/messages", HttpMethod.POST, request, CreateMessageResponse.class);

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.OK, retry.getStatusCode());
        assertEquals(first.getBody().messageId(), retry.getBody().messageId());
        assertEquals(first.getBody().aesKey(), retry.getBody().aesKey());
        assertEquals(Boolean.TRUE, retry.getBody().duplicate());
    }
}
