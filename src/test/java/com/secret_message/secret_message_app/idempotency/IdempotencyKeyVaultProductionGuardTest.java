package com.secret_message.secret_message_app.idempotency;

import com.secret_message.secret_message_app.utils.CryptoUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests (no Spring context) for the production/dev-fallback guard
 * in {@link IdempotencyKeyVault}. See docs/PASSWORD_HASHING_SPEC.md's
 * "Applicability to secret_message" section — this mirrors the pepper
 * guidance that a shared/default secret must never protect production data.
 */
class IdempotencyKeyVaultProductionGuardTest {

    private static final String DEV_FALLBACK_KEY = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";
    private static final String REAL_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; // 32 zero bytes, not the fallback

    private final CryptoUtil cryptoUtil = new CryptoUtil();

    @Test
    void production_withDevFallbackKey_throws() {
        assertThrows(IllegalStateException.class,
                () -> new IdempotencyKeyVault(DEV_FALLBACK_KEY, "production", cryptoUtil),
                "Booting with app.env=production and the checked-in dev fallback key must fail fast");
    }

    @Test
    void production_withRealKey_doesNotThrow() {
        assertDoesNotThrow(() -> new IdempotencyKeyVault(REAL_KEY, "production", cryptoUtil),
                "A non-default key in production must be accepted");
    }

    @Test
    void development_withDevFallbackKey_doesNotThrow() {
        assertDoesNotThrow(() -> new IdempotencyKeyVault(DEV_FALLBACK_KEY, "development", cryptoUtil),
                "The dev fallback key must remain usable outside production");
    }
}
