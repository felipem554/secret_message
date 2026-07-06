package com.secret_message.secret_message_app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.secret_message.secret_message_app.utils.WipingBase64Serializer;

/**
 * Create-response body. {@code aesKey} is held as raw bytes and written to
 * JSON as Base64 by {@link WipingBase64Serializer}, which zeroes the buffer
 * right after writing — response serialization is the last owner of the key
 * (docs/MEMORY_HARDENING.md, milestone 3). The JSON shape is unchanged:
 * {@code {"messageId":"...","aesKey":"<base64>"}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateMessageResponse(
        String messageId,
        @JsonSerialize(using = WipingBase64Serializer.class) byte[] aesKey,
        Boolean duplicate
) {
    public CreateMessageResponse(String messageId, byte[] aesKey) {
        this(messageId, aesKey, null);
    }
}
