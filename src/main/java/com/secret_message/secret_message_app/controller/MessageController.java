package com.secret_message.secret_message_app.controller;

import com.secret_message.secret_message_app.dto.CreateMessageRequest;
import com.secret_message.secret_message_app.dto.CreateMessageResponse;
import com.secret_message.secret_message_app.dto.RevealRequest;
import com.secret_message.secret_message_app.dto.RevealResponse;
import com.secret_message.secret_message_app.exception.InvalidRequestException;
import com.secret_message.secret_message_app.exception.MessageNotAvailableException;
import com.secret_message.secret_message_app.exception.PayloadTooLargeException;
import com.secret_message.secret_message_app.idempotency.IdempotencyRecord;
import com.secret_message.secret_message_app.idempotency.IdempotencyService;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import com.secret_message.secret_message_app.service.SecretMessageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Public HTTP API for the secret-message service.
 *
 * <p>Design decisions (see docs/HTTP_API_DESIGN.md):
 * - Message ID never appears in the URI — only in JSON bodies.
 * - Reveal is POST (not GET) because it is destructive.
 * - All reveal-failure cases return a uniform 404; see GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
public class MessageController {

    private final SecretMessageService secretMessageService;
    private final IdempotencyService idempotencyService;

    @Value("${app.max-message-size:1048576}")
    private long maxMessageSize;

    /**
     * Creates a new one-shot secret message.
     *
     * <p>If an {@code Idempotency-Key} header is present and matches a prior
     * request with the same body, returns the original response (HTTP 200 with
     * {@code duplicate:true}) without creating a second message. If the key is
     * reused with a different body, returns HTTP 409.
     */
    @PostMapping
    public ResponseEntity<CreateMessageResponse> create(
            @Valid @RequestBody CreateMessageRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        long contentLength = request.getContentLengthLong();
        if (contentLength > maxMessageSize
                || body.message().getBytes(StandardCharsets.UTF_8).length > maxMessageSize) {
            throw new PayloadTooLargeException(maxMessageSize);
        }

        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        if (normalizedIdempotencyKey != null) {
            String bodyHash = idempotencyService.hashBody(body.message());
            Optional<IdempotencyRecord> existing = idempotencyService.findExisting(normalizedIdempotencyKey, bodyHash);
            if (existing.isPresent()) {
                // recoverAesKey returns a fresh buffer; the response serializer wipes it.
                byte[] recoveredKey = idempotencyService.recoverAesKey(existing.get());
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(new CreateMessageResponse(existing.get().messageId(), recoveredKey, true));
            }
        }

        SecretMessageIdentifier identifier = secretMessageService.createSecretMessage(body.message());

        if (normalizedIdempotencyKey != null) {
            String bodyHash = idempotencyService.hashBody(body.message());
            boolean stored = idempotencyService.store(
                    normalizedIdempotencyKey, bodyHash, identifier.getMessageId(), identifier.getAeskey());
            if (!stored) {
                secretMessageService.discardSecretMessage(identifier.getMessageId());
                identifier.wipe();
                IdempotencyRecord existing = idempotencyService.findExisting(
                        normalizedIdempotencyKey, bodyHash).orElseThrow();
                byte[] recoveredKey = idempotencyService.recoverAesKey(existing);
                return ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .body(new CreateMessageResponse(existing.messageId(), recoveredKey, true));
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store")
                .body(new CreateMessageResponse(identifier.getMessageId(), identifier.getAeskey()));
    }

    /**
     * Reveals a secret message exactly once.
     *
     * <p>On success the plaintext is returned and the message is deleted.
     * Concurrent reveal losers and all other failure conditions (not found,
     * wrong key, attempts exhausted) return the same HTTP 404 with the same
     * body — see GlobalExceptionHandler.
     */
    @PostMapping("/reveal")
    public ResponseEntity<RevealResponse> reveal(@Valid @RequestBody RevealRequest body) {
        // The client-supplied key unavoidably arrives as a String in the request
        // body; decode it once here and pass only bytes to the service layer.
        // Undecodable Base64 becomes null, which the service counts as a failed
        // attempt like any other wrong key.
        byte[] keyBytes = decodeKeyOrNull(body.aesKey());
        try {
            String plaintext = secretMessageService.getEncryptedMessageById(
                    body.messageId(), keyBytes);
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(new RevealResponse(plaintext));
        } catch (MessageNotAvailableException e) {
            throw e;
        } catch (Exception e) {
            // Crypto exceptions (wrong key) -> same uniform 404
            throw new MessageNotAvailableException(MessageNotAvailableException.Reason.WRONG_KEY);
        } finally {
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    private static byte[] decodeKeyOrNull(String aesKeyBase64) {
        try {
            return Base64.getDecoder().decode(aesKeyBase64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        try {
            UUID uuid = UUID.fromString(normalized);
            if (uuid.version() != 4) {
                throw new InvalidRequestException("idempotency key must be a UUIDv4");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("idempotency key must be a UUIDv4");
        }
        return normalized;
    }
}
