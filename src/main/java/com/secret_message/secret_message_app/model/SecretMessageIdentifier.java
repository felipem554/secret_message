package com.secret_message.secret_message_app.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;

/**
 * Domain + NATS wire object for a created message. The per-message AES key is
 * held only as {@code byte[]} (docs/MEMORY_HARDENING.md): Jackson maps the
 * {@code aeskey} field to/from a Base64 JSON string, so the wire format is
 * unchanged while no application-owned {@code String} copy of the key exists.
 * Whoever writes the key to a transport boundary must call {@link #wipe()}
 * afterwards.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SecretMessageIdentifier {

    private String messageId;
    private byte[] aeskey;

    public void wipe() {
        if (aeskey != null) {
            Arrays.fill(aeskey, (byte) 0);
        }
    }
}
