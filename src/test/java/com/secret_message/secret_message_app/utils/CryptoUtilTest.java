package com.secret_message.secret_message_app.utils;

import org.junit.jupiter.api.Test;

import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {

    private final CryptoUtil crypto = new CryptoUtil();

    // ─── byte[] encrypt/decrypt ───────────────────────────────────────────────

    @Test
    void encrypt_decrypt_bytes_roundTrip() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();
        byte[] plaintext = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = crypto.encrypt(plaintext, key.getEncoded());
        byte[] recovered  = crypto.decrypt(ciphertext, key.getEncoded());

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encrypt_sameInputTwice_producesDistinctCiphertexts() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();
        byte[] plaintext = "same content".getBytes(StandardCharsets.UTF_8);

        byte[] c1 = crypto.encrypt(plaintext, key.getEncoded());
        byte[] c2 = crypto.encrypt(plaintext, key.getEncoded());

        assertFalse(Arrays.equals(c1, c2), "Random IV must produce different ciphertexts each call");
    }

    @Test
    void decrypt_wrongKey_throwsBadPaddingException() throws Exception {
        SecretKey k1 = crypto.generateRandomAESKey();
        SecretKey k2 = crypto.generateRandomAESKey();
        byte[] ciphertext = crypto.encrypt("data".getBytes(StandardCharsets.UTF_8), k1.getEncoded());

        assertThrows(BadPaddingException.class, () -> crypto.decrypt(ciphertext, k2.getEncoded()));
    }

    @Test
    void decrypt_tamperedCiphertext_throwsException() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();
        byte[] ciphertext = crypto.encrypt("tamper test".getBytes(StandardCharsets.UTF_8), key.getEncoded());
        ciphertext[20] ^= 0xFF;

        assertThrows(BadPaddingException.class, () -> crypto.decrypt(ciphertext, key.getEncoded()));
    }

    // ─── String encryptMessage/decryptMessage ─────────────────────────────────

    @Test
    void encryptMessage_decryptMessage_secretKey_roundTrip() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();
        String original = "Super secret 🔐 UTF-8 Ação";

        String encrypted = crypto.encryptMessage(original, key);
        String decrypted = crypto.decryptMessage(encrypted, key);

        assertEquals(original, decrypted);
    }

    @Test
    void encryptMessage_decryptMessage_base64StringKey_roundTrip() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();
        String aesKeyBase64 = Base64.getEncoder().encodeToString(key.getEncoded());
        String original = "Base64 key round-trip — 日本語";

        String encrypted = crypto.encryptMessage(original, key);
        String decrypted = crypto.decryptMessage(encrypted, aesKeyBase64);

        assertEquals(original, decrypted);
    }

    @Test
    void encryptMessage_emptyString_roundTrip() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();

        String encrypted = crypto.encryptMessage("", key);
        String decrypted = crypto.decryptMessage(encrypted, key);

        assertEquals("", decrypted);
    }

    // ─── Key generation ───────────────────────────────────────────────────────

    @Test
    void generateRandomAESKey_is256Bits() throws Exception {
        SecretKey key = crypto.generateRandomAESKey();

        assertEquals("AES", key.getAlgorithm());
        assertEquals(32, key.getEncoded().length, "AES-256 key must be 32 bytes");
    }

    @Test
    void generateRandomAESKey_eachCallUnique() throws Exception {
        SecretKey k1 = crypto.generateRandomAESKey();
        SecretKey k2 = crypto.generateRandomAESKey();

        assertFalse(Arrays.equals(k1.getEncoded(), k2.getEncoded()), "Keys must be random and unique");
    }

    // ─── PBKDF2 key derivation ─────────────────────────────────────────────────

    @Test
    void deriveKeyFromPassword_deterministicWithSameSalt() throws Exception {
        byte[] salt = crypto.generateSalt();

        SecretKey k1 = crypto.deriveKeyFromPassword("password123", salt);
        SecretKey k2 = crypto.deriveKeyFromPassword("password123", salt);

        assertArrayEquals(k1.getEncoded(), k2.getEncoded(),
                "Same password + salt must always produce the same derived key");
    }

    @Test
    void deriveKeyFromPassword_differentSalt_producesDistinctKey() throws Exception {
        byte[] salt1 = crypto.generateSalt();
        byte[] salt2 = crypto.generateSalt();

        SecretKey k1 = crypto.deriveKeyFromPassword("password123", salt1);
        SecretKey k2 = crypto.deriveKeyFromPassword("password123", salt2);

        assertFalse(Arrays.equals(k1.getEncoded(), k2.getEncoded()),
                "Different salts must produce different derived keys");
    }
}
