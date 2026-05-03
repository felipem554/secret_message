package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NatsService {

    private final Connection natsConnection;
    private final SecretMessageService secretMessageService;
    private final ObjectMapper mapper;

    @Value("${app.max-message-size:1048576}")
    private int maxMessageSize;

    private static final int MAX_MESSAGE_ID_LENGTH = 100;
    private static final int MAX_AES_KEY_LENGTH = 500;

    @EventListener(ApplicationReadyEvent.class)
    public void startNatsSubscriptions() {
        createDispatcher(natsConnection, "save.msg", this::createSecretMessageSubscriber);
        createDispatcher(natsConnection, "receive.msg", this::getSecretMessageSubscriber);
    }

    public void createDispatcher(Connection natsConnection, String subject, MessageHandler messageHandler) {
        Dispatcher dispatcher = natsConnection.createDispatcher(messageHandler);
        dispatcher.subscribe(subject);
    }

    public void createSecretMessageSubscriber(Message msg) {
        try {
            if (msg.getData() == null || msg.getData().length == 0) {
                log.warn("Received empty message for secret message creation");
                sendErrorResponse(msg.getReplyTo(), "Message cannot be empty");
                return;
            }

            if (msg.getData().length > maxMessageSize) {
                log.warn("Received message exceeding size limit: {} bytes", msg.getData().length);
                sendErrorResponse(msg.getReplyTo(), "Message size exceeds maximum allowed: " + maxMessageSize + " bytes");
                return;
            }

            String secretMessage = new String(msg.getData(), StandardCharsets.UTF_8);

            if (secretMessage.trim().isEmpty()) {
                log.warn("Received whitespace-only message");
                sendErrorResponse(msg.getReplyTo(), "Message cannot be empty or whitespace only");
                return;
            }

            if (msg.getReplyTo() != null) {
                try {
                    log.debug("Creating secret message with length: {} bytes", msg.getData().length);
                    SecretMessageIdentifier secretMessageIdentifier =
                            secretMessageService.createSecretMessage(secretMessage);

                    byte[] response = mapper.writeValueAsBytes(secretMessageIdentifier);
                    log.info("Secret message created successfully, replying to: {}", msg.getReplyTo());
                    natsConnection.publish(msg.getReplyTo(), response);
                } catch (Exception e) {
                    log.error("Error creating secret message", e);
                    sendErrorResponse(msg.getReplyTo(), "Failed to create secret message: " + e.getMessage());
                }
            } else {
                log.warn("No replyTo address provided in message");
            }
        } catch (Exception e) {
            log.error("Unexpected error in createSecretMessageSubscriber", e);
            if (msg.getReplyTo() != null) {
                sendErrorResponse(msg.getReplyTo(), "Internal server error");
            }
        }
    }

    public void getSecretMessageSubscriber(Message msg) {
        try {
            if (msg.getData() == null || msg.getData().length == 0) {
                log.warn("Received empty message for secret message retrieval");
                sendErrorResponse(msg.getReplyTo(), "Message identifier cannot be empty");
                return;
            }

            SecretMessageIdentifier messageIdentifier = mapper.readValue(msg.getData(), SecretMessageIdentifier.class);

            if (messageIdentifier == null) {
                log.warn("Failed to parse message identifier");
                sendErrorResponse(msg.getReplyTo(), "Invalid message identifier format");
                return;
            }

            if (messageIdentifier.getMessageId() == null || messageIdentifier.getMessageId().trim().isEmpty()) {
                log.warn("Received empty message ID");
                sendErrorResponse(msg.getReplyTo(), "Message ID cannot be empty");
                return;
            }

            if (messageIdentifier.getMessageId().length() > MAX_MESSAGE_ID_LENGTH) {
                log.warn("Message ID exceeds maximum length: {} characters", messageIdentifier.getMessageId().length());
                sendErrorResponse(msg.getReplyTo(), "Message ID too long");
                return;
            }

            if (messageIdentifier.getAeskey() == null || messageIdentifier.getAeskey().trim().isEmpty()) {
                log.warn("Received empty AES key");
                sendErrorResponse(msg.getReplyTo(), "AES key cannot be empty");
                return;
            }

            if (messageIdentifier.getAeskey().length() > MAX_AES_KEY_LENGTH) {
                log.warn("AES key exceeds maximum length: {} characters", messageIdentifier.getAeskey().length());
                sendErrorResponse(msg.getReplyTo(), "AES key too long");
                return;
            }

            if (msg.getReplyTo() != null) {
                try {
                    log.debug("Retrieving secret message with ID: {}", messageIdentifier.getMessageId());
                    String decryptedSecretMessage =
                            secretMessageService.getEncryptedMessageById(messageIdentifier.getMessageId(),
                                    messageIdentifier.getAeskey());

                    byte[] response = mapper.writeValueAsBytes(decryptedSecretMessage);
                    log.info("Secret message retrieved successfully, replying to: {}", msg.getReplyTo());
                    natsConnection.publish(msg.getReplyTo(), response);
                } catch (Exception e) {
                    log.error("Error retrieving secret message with ID: {}", messageIdentifier.getMessageId(), e);
                    sendErrorResponse(msg.getReplyTo(), "Failed to retrieve secret message: " + e.getMessage());
                }
            } else {
                log.warn("No replyTo address provided in message");
            }
        } catch (Exception e) {
            log.error("Unexpected error in getSecretMessageSubscriber", e);
            if (msg.getReplyTo() != null) {
                sendErrorResponse(msg.getReplyTo(), "Internal server error");
            }
        }
    }

    private void sendErrorResponse(String replyTo, String errorMessage) {
        if (replyTo == null || replyTo.isEmpty()) {
            log.warn("Cannot send error response - no replyTo address provided");
            return;
        }
        try {
            byte[] errorResponse = mapper.writeValueAsBytes(Map.of("error", errorMessage));
            natsConnection.publish(replyTo, errorResponse);
            log.debug("Sent error response to {}: {}", replyTo, errorMessage);
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }
}
