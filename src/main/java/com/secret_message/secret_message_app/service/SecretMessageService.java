package com.secret_message.secret_message_app.service;

import com.secret_message.secret_message_app.cache.RedisCacheManager;
import com.secret_message.secret_message_app.exception.MessageNotAvailableException;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecretMessageService {

    public static final String MAX_ATTEMPTS_MESSAGE = "Maximum attempts reached, the message has been deleted.";

    private final RedisCacheManager redisCacheManager;
    private final CryptoUtil cryptoUtil;

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
