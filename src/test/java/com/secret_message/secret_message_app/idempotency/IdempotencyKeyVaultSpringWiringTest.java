package com.secret_message.secret_message_app.idempotency;

import com.secret_message.secret_message_app.utils.CryptoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the production/dev-fallback guard actually fires through Spring's real
 * property-resolution chain (APP_ENV env var -> app.env property -> {@code @Value}
 * injection), not just via direct constructor calls. {@link IdempotencyKeyVaultProductionGuardTest}
 * covers the guard's decision logic in isolation; this covers the wiring around it.
 */
class IdempotencyKeyVaultSpringWiringTest {

    private static final String DEV_FALLBACK_KEY = "ZGV2ZWxvcG1lbnQtbWFzdGVyLWtleS0zMi1ieXRlcy0=";
    private static final String REAL_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(CryptoUtil.class)
            .withBean(IdempotencyKeyVault.class);

    @Test
    void contextFailsToStart_whenAppEnvProduction_andMasterKeyStillDevFallback() {
        contextRunner
                .withPropertyValues(
                        "app.env=production",
                        "app.idempotency.master-key=" + DEV_FALLBACK_KEY)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("development fallback"));
    }

    @Test
    void contextStartsSuccessfully_whenAppEnvProduction_withRealMasterKey() {
        contextRunner
                .withPropertyValues(
                        "app.env=production",
                        "app.idempotency.master-key=" + REAL_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void contextStartsSuccessfully_whenAppEnvDefaulted_withDevFallbackKey() {
        contextRunner
                .withPropertyValues("app.idempotency.master-key=" + DEV_FALLBACK_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
