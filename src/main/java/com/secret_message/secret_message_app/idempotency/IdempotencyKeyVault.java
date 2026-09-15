package com.secret_message.secret_message_app.idempotency;

import com.secret_message.secret_message_app.utils.CryptoUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Holds the Master Idempotency Encryption Key (MIEK) and exposes
 * AES-256-CBC encrypt/decrypt for per-message AES keys stored in
 * idempotency records.
 *
 * <p>Memory hardening (per docs/MEMORY_HARDENING.md):
 * - Master key is stored as byte[], never copied to String.
 * - Buffer is zeroed on application shutdown via @PreDestroy.
 * - The accessor methods accept and return byte[] only; callers are
 *   expected to zero their own buffers after use.
 *
 * <p>The encryption mode is intentionally CBC (no authenticity) — this is
 * the deliberate trade-off documented in HTTP_API_DESIGN.md §8. Tampering
 * with stored ciphertext produces a garbage key on decrypt; the resulting
 * three failed reveal attempts are bounded by the existing 3-strike counter.
 */
@Component
@Slf4j
public class IdempotencyKeyVault {

    private static final int REQUIRED_KEY_BYTES = 32;

    // The value checked into application.properties/compose.yaml/compose.ghcr.yaml as the
    // local-dev fallback for IDEMPOTENCY_MASTER_KEY. Never a real secret; safe to compare against.
    private static final String DEV_FALLBACK_KEY_BASE64 = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";

    private final byte[] masterKey;
    private final CryptoUtil cryptoUtil;

    @Autowired
    public IdempotencyKeyVault(
            @Value("${app.idempotency.master-key}") String masterKeyBase64,
            @Value("${app.env:development}") String appEnv,
            CryptoUtil cryptoUtil) {

        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "app.idempotency.master-key (env IDEMPOTENCY_MASTER_KEY) must be set. "
                            + "Generate one with: openssl rand -base64 32");
        }

        String trimmedKey = masterKeyBase64.trim();

        if ("production".equalsIgnoreCase(appEnv) && DEV_FALLBACK_KEY_BASE64.equals(trimmedKey)) {
            throw new IllegalStateException(
                    "app.idempotency.master-key is still the checked-in development fallback "
                            + "value while app.env (env APP_ENV) is production. Generate a real "
                            + "key with: openssl rand -base64 32");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.idempotency.master-key is not valid Base64", e);
        }

        if (decoded.length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.idempotency.master-key must decode to " + REQUIRED_KEY_BYTES
                            + " bytes (got " + decoded.length + ")");
        }

        this.masterKey = decoded;
        this.cryptoUtil = cryptoUtil;
        log.info("IdempotencyKeyVault initialized with a {}-byte master key", REQUIRED_KEY_BYTES);
    }

    /** Test convenience overload — defaults app.env to a non-production value. */
    IdempotencyKeyVault(String masterKeyBase64, CryptoUtil cryptoUtil) {
        this(masterKeyBase64, "development", cryptoUtil);
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            return cryptoUtil.encrypt(plaintext, masterKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("idempotency encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ivAndCiphertext) {
        try {
            return cryptoUtil.decrypt(ivAndCiphertext, masterKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("idempotency decryption failed", e);
        }
    }

    @PreDestroy
    void zeroize() {
        Arrays.fill(masterKey, (byte) 0);
    }
}
