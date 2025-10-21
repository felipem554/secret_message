package com.secret_message.secret_message_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secret_message.secret_message_app.config.NatsConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.secret_message.secret_message_app.model.SecretMessageIdentifier;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
public class NatsService {

    private final Connection natsConnection;

    @Autowired
    private SecretMessageService secretMessageService;

    @Autowired
    private ObjectMapper mapper;

//    public NatsService(Connection natsConnection) {
//        this.natsConnection = natsConnection;
//    }

    @EventListener(ApplicationReadyEvent.class)
    public void startNatsSubscripions(){
        createDispacher(natsConnection, "save.msg", this::createSecretMessageSubscriber);
        createDispacher(natsConnection, "receive.msg", this::getSecretMessageSubscriber);
    }

    public void createDispacher(Connection natsConnection, String subject, MessageHandler messageHandler){
        Dispatcher dispatcher = natsConnection.createDispatcher(messageHandler);
        dispatcher.subscribe(subject);
    }

    public void createSecretMessageSubscriber(Message msg) {
        try {
            System.out.println("Bug founded!");
            String secretMessage = new String(msg.getData());
            if (msg.getReplyTo() != null && !secretMessage.isEmpty()) {
                try {
                    SecretMessageIdentifier secretMessageIdentifier =
                            secretMessageService.createSecretMessage(secretMessage);

//                    byte[] response = mapper.writeValueAsBytes(secretMessageIdentifier);
                    byte[] response = mapper.writeValueAsBytes("haaaa fdppppppx");

                    System.out.println("Replying to: " + msg.getReplyTo());

                    natsConnection.publish(msg.getReplyTo(), response);

                } catch (Exception e) {
                    e.printStackTrace();
                    //TODO
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // TODO proper error handling
            // next step would be create a @ControllerAdvice and personalized exceptions to proper handling the error messages to be more user friendly.
        }
    }

    public void getSecretMessageSubscriber(Message msg) {
        try {
            SecretMessageIdentifier messageIdentifier = mapper.readValue(msg.getData(), SecretMessageIdentifier.class);
            if (msg.getReplyTo() != null && !messageIdentifier.getMessageId().isEmpty()
                    && !messageIdentifier.getAeskey().isEmpty()) {
                try {
                    String decryptedSecretMessage =
                            secretMessageService.getEncryptedMessageById(messageIdentifier.getMessageId(),
                                    messageIdentifier.getAeskey());

                    byte[] response = mapper.writeValueAsBytes(decryptedSecretMessage);

                    System.out.println("Replying to: " + msg.getReplyTo());

                    natsConnection.publish(msg.getReplyTo(), response);

                } catch (Exception e) {
                    e.printStackTrace();
                    //TODO
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // TODO proper error handling
        }
    }
}
