package com.secret_message.secret_message_app.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.exception.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Read/write idempotency records in Redis. The AES key inside each record
 * is encrypted with the master key (MIEK) via IdempotencyKeyVault before
 * storage; this service never persists the key in plaintext.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final IdempotencyKeyVault vault;
    private final ObjectMapper mapper;

    @Value("${app.auto-delete-days}")
    private long ttlDays;

    private String buildKey(String idempotencyKey) {
        return "idempotency:" + idempotencyKey;
    }

    /**
     * Computes a stable hash of the request body for replay detection.
     * SHA-256 hex output; not used for any cryptographic guarantee, only
     * to detect "same Idempotency-Key, different body" replay attempts.
     */
    public String hashBody(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Returns the existing record if the same idempotency key + body hash
     * combination has been seen before. Throws IdempotencyConflictException
     * if the key was used with a different body.
     */
    public Optional<IdempotencyRecord> findExisting(String idempotencyKey, String bodyHash) {
        String json = redisTemplate.opsForValue().get(buildKey(idempotencyKey));
        if (json == null) {
            return Optional.empty();
        }
        IdempotencyRecord record = parse(json);
        if (!record.bodyHash().equals(bodyHash)) {
            throw new IdempotencyConflictException();
        }
        return Optional.of(record);
    }

    /**
     * Stores a new record. The aesKeyBase64 is decoded, encrypted with MIEK,
     * and stored as Base64-encoded ciphertext. The plaintext AES key buffer
     * is zeroed before this method returns.
     */
    public void store(String idempotencyKey, String bodyHash, String messageId, String aesKeyBase64) {
        byte[] aesKeyBytes = Base64.getDecoder().decode(aesKeyBase64);
        byte[] encrypted;
        try {
            encrypted = vault.encrypt(aesKeyBytes);
        } finally {
            Arrays.fill(aesKeyBytes, (byte) 0);
        }

        IdempotencyRecord record = new IdempotencyRecord(
                bodyHash,
                messageId,
                Base64.getEncoder().encodeToString(encrypted),
                System.currentTimeMillis()
        );

        String json;
        try {
            json = mapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize idempotency record", e);
        }

        redisTemplate.opsForValue().setIfAbsent(
                buildKey(idempotencyKey),
                json,
                Duration.ofDays(ttlDays)
        );
    }

    /**
     * Decrypts the AES key from a stored record and returns it as Base64.
     * Used on idempotent retry to return the original key to the client.
     */
    public String recoverAesKey(IdempotencyRecord record) {
        byte[] ciphertext = Base64.getDecoder().decode(record.encryptedAesKey());
        byte[] decrypted = vault.decrypt(ciphertext);
        try {
            return Base64.getEncoder().encodeToString(decrypted);
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    private IdempotencyRecord parse(String json) {
        try {
            return mapper.readValue(json, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse idempotency record", e);
        }
    }
}
