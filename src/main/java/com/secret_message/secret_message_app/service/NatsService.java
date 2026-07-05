package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.exception.MessageNotAvailableException;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
    private static final int MAX_AES_KEY_BYTES = 64;

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
                sendErrorResponse(msg.getReplyTo(), "Message cannot be empty");
                return;
            }
            if (msg.getData().length > maxMessageSize) {
                sendErrorResponse(msg.getReplyTo(), "Message size exceeds maximum allowed: " + maxMessageSize + " bytes");
                return;
            }

            String secretMessage = new String(msg.getData(), StandardCharsets.UTF_8);
            if (secretMessage.trim().isEmpty()) {
                sendErrorResponse(msg.getReplyTo(), "Message cannot be empty or whitespace only");
                return;
            }

            if (msg.getReplyTo() != null) {
                SecretMessageIdentifier identifier = null;
                try {
                    identifier = secretMessageService.createSecretMessage(secretMessage);
                    // Jackson writes the byte[] key as Base64 (wire format unchanged);
                    // this boundary owns the key bytes, so wipe them after publish.
                    natsConnection.publish(msg.getReplyTo(), mapper.writeValueAsBytes(identifier));
                } catch (Exception e) {
                    log.error("Error creating secret message", e);
                    sendErrorResponse(msg.getReplyTo(), "Failed to create secret message: " + e.getMessage());
                } finally {
                    if (identifier != null) {
                        identifier.wipe();
                    }
                }
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
                sendErrorResponse(msg.getReplyTo(), "Message identifier cannot be empty");
                return;
            }

            SecretMessageIdentifier messageIdentifier;
            try {
                // Jackson decodes the Base64 "aeskey" JSON field into byte[].
                messageIdentifier = mapper.readValue(msg.getData(), SecretMessageIdentifier.class);
            } catch (Exception e) {
                sendErrorResponse(msg.getReplyTo(), "Invalid message identifier format");
                return;
            }
            if (messageIdentifier == null) {
                sendErrorResponse(msg.getReplyTo(), "Invalid message identifier format");
                return;
            }

            String msgId = messageIdentifier.getMessageId();
            byte[] aesKey = messageIdentifier.getAeskey();

            if (msgId == null || msgId.trim().isEmpty() || msgId.length() > MAX_MESSAGE_ID_LENGTH) {
                sendErrorResponse(msg.getReplyTo(), "Invalid message ID");
                return;
            }
            if (aesKey == null || aesKey.length == 0 || aesKey.length > MAX_AES_KEY_BYTES) {
                sendErrorResponse(msg.getReplyTo(), "Invalid AES key");
                return;
            }

            if (msg.getReplyTo() != null) {
                try {
                    String decrypted = secretMessageService.getEncryptedMessageById(msgId, aesKey);
                    natsConnection.publish(msg.getReplyTo(), mapper.writeValueAsBytes(decrypted));
                } catch (MessageNotAvailableException e) {
                    // Preserve the original NATS contract for max-attempts exhaustion
                    String clientMessage = e.getReason() == MessageNotAvailableException.Reason.EXHAUSTED
                            ? SecretMessageService.MAX_ATTEMPTS_MESSAGE
                            : "Message not available";
                    sendErrorResponse(msg.getReplyTo(), clientMessage);
                } catch (Exception e) {
                    log.error("Error retrieving secret message with ID: {}", msgId, e);
                    sendErrorResponse(msg.getReplyTo(), "Failed to retrieve secret message: " + e.getMessage());
                } finally {
                    messageIdentifier.wipe();
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in getSecretMessageSubscriber", e);
            if (msg.getReplyTo() != null) {
                sendErrorResponse(msg.getReplyTo(), "Internal server error");
            }
        }
    }

    private void sendErrorResponse(String replyTo, String errorMessage) {
        if (replyTo == null || replyTo.isEmpty()) return;
        try {
            natsConnection.publish(replyTo, mapper.writeValueAsBytes(Map.of("error", errorMessage)));
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }
}
