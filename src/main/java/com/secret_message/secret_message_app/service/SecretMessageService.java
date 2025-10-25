package com.secret_message.secret_message_app.service;

import com.secret_message.secret_message_app.cache.RedisCacheManager;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import com.secret_message.secret_message_app.utils.CryptoUtil;
import com.secret_message.secret_message_app.utils.PasswordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private RedisCacheManager redisCacheManager;

    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired
    private PasswordGenerator passwordGenerator;

    @Value("${app.password-length}")
    private int passwordLength;


    public SecretMessageIdentifier createSecretMessage(String secretMessage) {
        String messageId = UUID.randomUUID().toString();

        try {
            // Generate a random AES key instead of deriving from password
            SecretKey secretKey = cryptoUtil.generateRandomAESKey();
            String encryptedMessage = cryptoUtil.encryptMessage(secretMessage, secretKey);

            redisCacheManager.storeEncryptedMessage(messageId, encryptedMessage);

            return new SecretMessageIdentifier(messageId, secretKey); // Return the message ID and AES key

        } catch (Exception e) {
            // Handle encryption errors, possibly throw a custom exception or handle the error case appropriately
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String getEncryptedMessageById(String messageId, String aesKey) throws InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException {

        if (redisCacheManager.incrementAndCheckAttempt(messageId)) {
            redisCacheManager.deleteEncryptedMessage(messageId);
            return "Maximum attempts reached, the message has been deleted.";
        }
        try {
            String encryptedMessage = redisCacheManager.getEncryptedMessageById(messageId);
            String decryptedMessage = cryptoUtil.decryptMessage(encryptedMessage, aesKey);

            // Message being deleted after successful decryption
            redisCacheManager.deleteEncryptedMessage(messageId);
            redisCacheManager.resetAttempt(messageId);
            return decryptedMessage;
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException |
                 InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
