package com.secret_message.secret_message_app.service;

import com.secret_message.secret_message_app.cache.RedisCacheManager;
import com.secret_message.secret_message_app.exception.InvalidRequestException;
import com.secret_message.secret_message_app.exception.MessageNotAvailableException;
import com.secret_message.secret_message_app.idempotency.IdempotencyRecord;
import com.secret_message.secret_message_app.idempotency.IdempotencyService;
import com.secret_message.secret_message_app.model.CreateMessageResult;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import com.secret_message.secret_message_app.utils.CryptoUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecretMessageService {

    public static final String MAX_ATTEMPTS_MESSAGE = "Maximum attempts reached, the message has been deleted.";

    private final RedisCacheManager redisCacheManager;
    private final CryptoUtil cryptoUtil;
    private final IdempotencyService idempotencyService;

    /**
     * Creates an encrypted message and returns its identifier. Key-buffer
     * ownership (docs/MEMORY_HARDENING.md): on success the returned identifier
     * owns the key bytes and the transport boundary that writes them to the
     * client must wipe them; on failure this method wipes them itself.
     */
    public SecretMessageIdentifier createSecretMessage(String secretMessage) {
        String messageId = UUID.randomUUID().toString();
        byte[] keyBytes = cryptoUtil.generateRandomAESKeyBytes();
        try {
            String encryptedMessage = cryptoUtil.encryptMessage(secretMessage, keyBytes);
            redisCacheManager.storeEncryptedMessage(messageId, encryptedMessage);
            return new SecretMessageIdentifier(messageId, keyBytes);
        } catch (Exception e) {
            Arrays.fill(keyBytes, (byte) 0);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Creates a message, honouring an optional idempotency key so that a
     * retried request returns the original message instead of creating a
     * second one.
     *
     * <p>A {@code null} or blank key skips idempotency entirely. A key that is
     * present but not a UUIDv4 is rejected with {@link InvalidRequestException}.
     * Reusing a key with a different body raises
     * {@code IdempotencyConflictException} from {@link IdempotencyService}.
     *
     * <p>This lives in the service rather than in a transport adapter so that
     * every entry point gets the same behaviour, including the race handling
     * below: the request that loses {@code SETNX} discards its own message and
     * returns the winner's.
     */
    public CreateMessageResult createSecretMessage(String secretMessage, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);

        if (normalizedKey == null) {
            SecretMessageIdentifier identifier = createSecretMessage(secretMessage);
            return new CreateMessageResult(identifier.getMessageId(), identifier.getAeskey(), false);
        }

        String bodyHash = idempotencyService.hashBody(secretMessage);

        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(normalizedKey, bodyHash);
        if (existing.isPresent()) {
            return replayOf(existing.get());
        }

        SecretMessageIdentifier identifier = createSecretMessage(secretMessage);
        boolean stored = idempotencyService.store(
                normalizedKey, bodyHash, identifier.getMessageId(), identifier.getAeskey());

        if (!stored) {
            // Another request created the record first; drop ours and replay theirs.
            discardSecretMessage(identifier.getMessageId());
            identifier.wipe();
            IdempotencyRecord winner = idempotencyService.findExisting(normalizedKey, bodyHash).orElseThrow();
            return replayOf(winner);
        }

        return new CreateMessageResult(identifier.getMessageId(), identifier.getAeskey(), false);
    }

    /** recoverAesKey returns a fresh buffer; the response serializer wipes it. */
    private CreateMessageResult replayOf(IdempotencyRecord record) {
        return new CreateMessageResult(record.messageId(), idempotencyService.recoverAesKey(record), true);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        try {
            if (UUID.fromString(normalized).version() != 4) {
                throw new InvalidRequestException("idempotency key must be a UUIDv4");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("idempotency key must be a UUIDv4");
        }
        return normalized;
    }

    /**
     * Deletes a newly-created message that lost an idempotent create race.
     */
    public void discardSecretMessage(String messageId) {
        redisCacheManager.deleteEncryptedMessage(messageId);
        redisCacheManager.resetAttempt(messageId);
    }

    /**
     * Reveals a message exactly once. Only failed decryptions count toward
     * the 3-strike limit; a correct key remains valid after one or two wrong
     * attempts. All reveal failures are normalized for the HTTP layer.
     *
     * <p>{@code keyBytes} may be {@code null} when the transport boundary
     * could not Base64-decode the client-supplied key; that counts as a
     * failed attempt exactly like a well-formed wrong key. The caller owns
     * (and must wipe) the key buffer.
     */
    public String getEncryptedMessageById(String messageId, byte[] keyBytes) {
        try {
            String encryptedMessage = redisCacheManager.getEncryptedMessageById(messageId);
            if (encryptedMessage == null) {
                throw new MessageNotAvailableException(MessageNotAvailableException.Reason.NOT_FOUND);
            }
            if (keyBytes == null) {
                throw new InvalidKeyException("undecodable key");
            }
            String decryptedMessage = cryptoUtil.decryptMessage(encryptedMessage, keyBytes);
            if (!redisCacheManager.deleteIfPresent(messageId)) {
                throw new MessageNotAvailableException(MessageNotAvailableException.Reason.RACE_LOST);
            }
            redisCacheManager.resetAttempt(messageId);
            return decryptedMessage;
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException |
                 InvalidAlgorithmParameterException | IllegalBlockSizeException |
                 BadPaddingException | IllegalArgumentException e) {
            if (redisCacheManager.incrementAndCheckAttempt(messageId)) {
                throw new MessageNotAvailableException(MessageNotAvailableException.Reason.EXHAUSTED);
            }
            throw new MessageNotAvailableException(MessageNotAvailableException.Reason.WRONG_KEY);
        } catch (MessageNotAvailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
