package com.secret_message.secret_message_app.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.crypto.SecretKey;
import java.util.Base64;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class SecretMessageIdentifier {

    private String messageId;
    @JsonIgnore
    private SecretKey secretKey;
    private String aeskey;

    public SecretMessageIdentifier(String messageId, SecretKey secretKey) {
        this.secretKey = secretKey;
        this.messageId = messageId;
        this.aeskey =  Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    public String toJson() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting message to JSON", e);
        }
    }
}