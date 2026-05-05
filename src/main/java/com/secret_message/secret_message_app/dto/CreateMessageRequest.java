package com.secret_message.secret_message_app.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateMessageRequest(
        @NotBlank(message = "message must not be blank") String message
) {
}
