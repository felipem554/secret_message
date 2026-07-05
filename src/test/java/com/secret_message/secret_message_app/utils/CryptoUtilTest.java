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
        byte[] key = crypto.generateRandomAESKeyBytes();
        byte[] plaintext = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = crypto.encrypt(plaintext, key);
        byte[] recovered  = crypto.decrypt(ciphertext, key);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void encrypt_sameInputTwice_producesDistinctCiphertexts() throws Exception {
        byte[] key = crypto.generateRandomAESKeyBytes();
        byte[] plaintext = "same content".getBytes(StandardCharsets.UTF_8);

        byte[] c1 = crypto.encrypt(plaintext, key);
        byte[] c2 = crypto.encrypt(plaintext, key);

        assertFalse(Arrays.equals(c1, c2), "Random IV must produce different ciphertexts each call");
    }

    @Test
    void decrypt_wrongKey_throwsBadPaddingException() throws Exception {
        byte[] k1 = crypto.generateRandomAESKeyBytes();
        byte[] k2 = crypto.generateRandomAESKeyBytes();
        byte[] ciphertext = crypto.encrypt("data".getBytes(StandardCharsets.UTF_8), k1);

        assertThrows(BadPaddingException.class, () -> crypto.decrypt(ciphertext, k2));
    }

    @Test
    void decrypt_tamperedCiphertext_throwsException() throws Exception {
        byte[] key = crypto.generateRandomAESKeyBytes();
        byte[] ciphertext = crypto.encrypt("tamper test".getBytes(StandardCharsets.UTF_8), key);
        ciphertext[20] ^= 0xFF;

        assertThrows(BadPaddingException.class, () -> crypto.decrypt(ciphertext, key));
    }

    // ─── String encryptMessage/decryptMessage ─────────────────────────────────

    @Test
    void encryptMessage_decryptMessage_roundTrip() throws Exception {
        byte[] key = crypto.generateRandomAESKeyBytes();
        String original = "Super secret 🔐 UTF-8 Ação";

        String encrypted = crypto.encryptMessage(original, key);
        String decrypted = crypto.decryptMessage(encrypted, key);

        assertEquals(original, decrypted);
    }

    @Test
    void encryptMessage_decryptMessage_survivesBase64WireRoundTrip() throws Exception {
        // Simulates the client flow: key travels as Base64 text and is
        // decoded back to bytes at the transport boundary before decryption.
        byte[] key = crypto.generateRandomAESKeyBytes();
        String aesKeyBase64 = Base64.getEncoder().encodeToString(key);
        String original = "Base64 key round-trip — 日本語";

        String encrypted = crypto.encryptMessage(original, key);
        String decrypted = crypto.decryptMessage(encrypted, Base64.getDecoder().decode(aesKeyBase64));

        assertEquals(original, decrypted);
    }

    @Test
    void encryptMessage_emptyString_roundTrip() throws Exception {
        byte[] key = crypto.generateRandomAESKeyBytes();

        String encrypted = crypto.encryptMessage("", key);
        String decrypted = crypto.decryptMessage(encrypted, key);

        assertEquals("", decrypted);
    }

    // ─── Key generation ───────────────────────────────────────────────────────

    @Test
    void generateRandomAESKeyBytes_is256Bits() {
        byte[] key = crypto.generateRandomAESKeyBytes();

        assertEquals(32, key.length, "AES-256 key must be 32 bytes");
    }

    @Test
    void generateRandomAESKeyBytes_eachCallUnique() {
        byte[] k1 = crypto.generateRandomAESKeyBytes();
        byte[] k2 = crypto.generateRandomAESKeyBytes();

        assertFalse(Arrays.equals(k1, k2), "Keys must be random and unique");
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
