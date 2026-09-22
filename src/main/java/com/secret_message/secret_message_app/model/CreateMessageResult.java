package com.secret_message.secret_message_app.model;

/**
 * Outcome of a create request, including whether it was served from an
 * existing idempotency record rather than creating a new message.
 *
 * <p>Key-buffer ownership (docs/MEMORY_HARDENING.md): the caller owns
 * {@code aesKey} and the transport boundary that writes it to the client must
 * wipe it. On the HTTP path that is {@code WipingBase64Serializer}.
 */
public record CreateMessageResult(String messageId, byte[] aesKey, boolean duplicate) {
}
