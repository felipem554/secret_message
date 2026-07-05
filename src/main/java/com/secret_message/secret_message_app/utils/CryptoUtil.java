package com.secret_message.secret_message_app.utils;

import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-CBC primitives. Per docs/MEMORY_HARDENING.md the message-key API is
 * byte[]-only: no method accepts or returns a per-message AES key as a
 * {@code String} or {@code SecretKey}, so callers can zero their key buffers.
 * The {@link SecretKeySpec} instances created inside encrypt/decrypt hold
 * JCE-internal copies that cannot be wiped; they are short-lived garbage.
 */
@Service
public class CryptoUtil {

    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String SECRET_KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;
    private static final int IV_LENGTH = 16;
    private static final SecureRandom secureRandom = new SecureRandom();

    public byte[] encrypt(byte[] plaintext, byte[] keyBytes)
            throws NoSuchPaddingException, NoSuchAlgorithmException,
                   InvalidAlgorithmParameterException, InvalidKeyException,
                   IllegalBlockSizeException, BadPaddingException {

        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, ENCRYPTION_ALGORITHM),
                new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] out = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
        return out;
    }

    public byte[] decrypt(byte[] ivAndCiphertext, byte[] keyBytes)
            throws NoSuchPaddingException, NoSuchAlgorithmException,
                   InvalidAlgorithmParameterException, InvalidKeyException,
                   IllegalBlockSizeException, BadPaddingException {

        byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, IV_LENGTH, ivAndCiphertext.length);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, ENCRYPTION_ALGORITHM),
                new IvParameterSpec(iv));
        return cipher.doFinal(ciphertext);
    }

    public String encryptMessage(String content, byte[] keyBytes)
            throws NoSuchPaddingException, NoSuchAlgorithmException,
                   InvalidAlgorithmParameterException, InvalidKeyException,
                   IllegalBlockSizeException, BadPaddingException {

        byte[] result = encrypt(content.getBytes(StandardCharsets.UTF_8), keyBytes);
        return Base64.getEncoder().encodeToString(result);
    }

    public String decryptMessage(String encryptedContent, byte[] keyBytes)
            throws NoSuchPaddingException, NoSuchAlgorithmException,
                   InvalidAlgorithmParameterException, InvalidKeyException,
                   IllegalBlockSizeException, BadPaddingException {

        byte[] decoded = Base64.getDecoder().decode(encryptedContent);
        byte[] decrypted = decrypt(decoded, keyBytes);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * Generates a random AES-256 key as raw bytes. An AES key is just random
     * bytes, so drawing from SecureRandom directly avoids the unwipeable
     * internal copy a {@code KeyGenerator}-produced {@code SecretKey} would
     * hold. The caller owns the returned buffer and must zero it after use.
     */
    public byte[] generateRandomAESKeyBytes() {
        byte[] keyBytes = new byte[KEY_LENGTH / 8];
        secureRandom.nextBytes(keyBytes);
        return keyBytes;
    }

    public SecretKey deriveKeyFromPassword(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {

        SecretKeyFactory factory = SecretKeyFactory.getInstance(SECRET_KEY_FACTORY_ALGORITHM);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey secretKey = factory.generateSecret(spec);
        return new SecretKeySpec(secretKey.getEncoded(), ENCRYPTION_ALGORITHM);
    }

    public byte[] generateSalt() {
        byte[] salt = new byte[IV_LENGTH];
        secureRandom.nextBytes(salt);
        return salt;
    }
}
