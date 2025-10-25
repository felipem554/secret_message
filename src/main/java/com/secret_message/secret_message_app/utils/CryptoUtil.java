package com.secret_message.secret_message_app.utils;

import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;

@Service
public class CryptoUtil {

    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String SECRET_KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;
    private static final int IV_LENGTH = 16;
    private static final SecureRandom secureRandom = new SecureRandom();

    public String encryptMessage(String content, SecretKey aesKey) throws Exception {

        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, ivSpec);

        byte[] encryptedData = cipher.doFinal(content.getBytes());

        byte[] encryptedMessageWithIv = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, encryptedMessageWithIv, 0, iv.length);
        System.arraycopy(encryptedData, 0, encryptedMessageWithIv, iv.length, encryptedData.length);

        return Base64.getEncoder().encodeToString(encryptedMessageWithIv);
    }

    public String decryptMessage(String encryptedContent, SecretKey secretKey) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {

        byte[] decodedData = Base64.getDecoder().decode(encryptedContent);
        byte[] iv = Arrays.copyOfRange(decodedData, 0, IV_LENGTH);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        byte[] encryptedData = Arrays.copyOfRange(decodedData, IV_LENGTH, decodedData.length);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

        byte[] decryptedData = cipher.doFinal(encryptedData);
        return new String(decryptedData);
    }

    public String decryptMessage(String encryptedContent, String aesKey) throws InvalidAlgorithmParameterException,
            NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException {
        byte[] decodedKey = Base64.getDecoder().decode(aesKey);
        SecretKey secretKey = new SecretKeySpec(decodedKey, ENCRYPTION_ALGORITHM);
        return decryptMessage(encryptedContent, secretKey);
    }

    /**
     * Generates a random AES-256 key for encryption.
     * This is more secure than password-based key derivation for random keys.
     * 
     * @return A SecretKey for AES encryption
     * @throws NoSuchAlgorithmException if AES algorithm is not available
     */
    public SecretKey generateRandomAESKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM);
        keyGenerator.init(KEY_LENGTH, secureRandom);
        return keyGenerator.generateKey();
    }

    /**
     * Derives a key from a password using PBKDF2.
     * Note: The salt is randomly generated each time, so the same password will produce different keys.
     * For proper password-based encryption, the salt should be stored with the encrypted data.
     * 
     * @param password The password to derive the key from
     * @param salt The salt to use for key derivation (must be stored with encrypted data)
     * @return A SecretKey for AES encryption
     * @throws NoSuchAlgorithmException if PBKDF2 algorithm is not available
     * @throws InvalidKeySpecException if the key specification is invalid
     */
    public SecretKey deriveKeyFromPassword(String password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(SECRET_KEY_FACTORY_ALGORITHM);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = factory.generateSecret(spec);
        return new SecretKeySpec(secretKey.getEncoded(), ENCRYPTION_ALGORITHM);
    }
    
    /**
     * Generates a random salt for password-based key derivation.
     * 
     * @return A byte array containing the random salt
     */
    public byte[] generateSalt() {
        byte[] salt = new byte[IV_LENGTH];
        secureRandom.nextBytes(salt);
        return salt;
    }
}