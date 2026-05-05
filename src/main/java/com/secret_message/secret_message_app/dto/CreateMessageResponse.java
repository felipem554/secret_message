package com.secret_message.secret_message_app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateMessageResponse(
        String messageId,
        String aesKey,
        Boolean duplicate
) {
    public CreateMessageResponse(String messageId, String aesKey) {
        this(messageId, aesKey, null);
    }
}
