package com.secret_message.secret_message_app.idempotency;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Persisted shape of an idempotency record in Redis.
 * The encrypted AES key is Base64-encoded ciphertext (IV || AES-CBC ciphertext)
 * produced by IdempotencyKeyVault.encrypt() and is decryptable only by the
 * server holding the matching master key.
 */
public record IdempotencyRecord(
        @JsonProperty("body_hash") String bodyHash,
        @JsonProperty("message_id") String messageId,
        @JsonProperty("encrypted_aes_key") String encryptedAesKey,
        @JsonProperty("created_at") long createdAt
) {
}
