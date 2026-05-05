package com.secret_message.secret_message_app.dto;

import jakarta.validation.constraints.NotBlank;

public record RevealRequest(
        @NotBlank(message = "messageId must not be blank") String messageId,
        @NotBlank(message = "aesKey must not be blank") String aesKey
) {
}
