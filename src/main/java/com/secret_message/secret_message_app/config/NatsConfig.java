package com.secret_message.secret_message_app.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Configuration for NATS messaging system.
 * Supports both authenticated and unauthenticated connections.
 */
@Configuration
public class NatsConfig {

    @Value("${NATS_URL}")
    private String natsUrl;

    @Value("${NATS_USER:#{null}}")
    private String natsUser;

    @Value("${NATS_PASS:#{null}}")
    private String natsPassword;

    /**
     * Creates a NATS connection with optional authentication.
     * If NATS_USER and NATS_PASS are provided, authentication is enabled.
     * 
     * @return A NATS Connection
     * @throws IOException if connection fails
     * @throws InterruptedException if connection is interrupted
     * @throws TimeoutException if connection times out
     */
    @Bean
    public Connection natsConnection() throws IOException, InterruptedException, TimeoutException {
        Options.Builder optionsBuilder = new Options.Builder()
                .server(natsUrl);

        // Add authentication if credentials are provided
        if (natsUser != null && !natsUser.isEmpty() && 
            natsPassword != null && !natsPassword.isEmpty()) {
            optionsBuilder.userInfo(natsUser, natsPassword);
        }

        return Nats.connect(optionsBuilder.build());
    }
}
