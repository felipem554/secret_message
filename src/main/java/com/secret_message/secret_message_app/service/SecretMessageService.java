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
import javax.crypto.SecretKey;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecretMessageService {

    public static final String MAX_ATTEMPTS_MESSAGE = "Maximum attempts reached, the message has been deleted.";

    private final RedisCacheManager redisCacheManager;
    private final CryptoUtil cryptoUtil;

    public SecretMessageIdentifier createSecretMessage(String secretMessage) {
        String messageId = UUID.randomUUID().toString();
        try {
            SecretKey secretKey = cryptoUtil.generateRandomAESKey();
            String encryptedMessage = cryptoUtil.encryptMessage(secretMessage, secretKey);
            redisCacheManager.storeEncryptedMessage(messageId, encryptedMessage);
            return new SecretMessageIdentifier(messageId, secretKey);
        } catch (Exception e) {
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
     */
    public String getEncryptedMessageById(String messageId, String aesKey) {
        try {
            String encryptedMessage = redisCacheManager.getEncryptedMessageById(messageId);
            if (encryptedMessage == null) {
                throw new MessageNotAvailableException(MessageNotAvailableException.Reason.NOT_FOUND);
            }
            String decryptedMessage = cryptoUtil.decryptMessage(encryptedMessage, aesKey);
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
