package com.secret_message.secret_message_app.idempotency;

import com.secret_message.secret_message_app.utils.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class IdempotencyKeyVaultTest {

    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7.0.5").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> natsContainer =
            new GenericContainer<>("nats:2.10.7-alpine").withExposedPorts(4222);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redisContainer::getHost);
        registry.add("spring.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("nats.server.url",
                () -> "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222));
    }

    @Autowired
    private IdempotencyKeyVault vault;

    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void encryptDecrypt_roundTrip_restoresOriginal() {
        byte[] original = "test-aes-key-32-bytes-exactly!!!".getBytes();

        byte[] ciphertext = vault.encrypt(original);
        byte[] recovered = vault.decrypt(ciphertext);

        assertArrayEquals(original, recovered, "Decrypt must restore the original plaintext");
    }

    @Test
    void encrypt_producesRandomIv_differentCiphertextsForSamePlaintext() {
        byte[] plaintext = "same-content-each-time-32-bytes!".getBytes();

        byte[] c1 = vault.encrypt(plaintext);
        byte[] c2 = vault.encrypt(plaintext);

        assertFalse(Arrays.equals(c1, c2),
                "Each encrypt call must use a fresh random IV, producing distinct ciphertext");
    }

    @Test
    void decrypt_withTamperedCiphertext_throwsException() {
        byte[] plaintext = "some-aes-key-32-bytes-padded-xx!".getBytes();
        byte[] ciphertext = vault.encrypt(plaintext);

        // Flip a bit in the ciphertext portion (after the 16-byte IV)
        ciphertext[20] ^= 0xFF;

        assertThrows(IllegalStateException.class,
                () -> vault.decrypt(ciphertext),
                "Tampered ciphertext should cause decryption to throw");
    }

    @Test
    void decrypt_withWrongMasterKey_throwsException() {
        // Build a second vault with a different key to simulate wrong MIEK
        String differentKey = "d3JvbmdtYXN0ZXJrZXkzMmJ5dGVzc3M="; // "wrongmasterkey32bytesss" padded
        IdempotencyKeyVault wrongVault = new IdempotencyKeyVault(differentKey, cryptoUtil);

        byte[] plaintext = "aes-key-material-32-bytes-padded".getBytes();
        byte[] ciphertext = vault.encrypt(plaintext);

        assertThrows(IllegalStateException.class,
                () -> wrongVault.decrypt(ciphertext),
                "Decryption with a different master key must fail");
    }
}
