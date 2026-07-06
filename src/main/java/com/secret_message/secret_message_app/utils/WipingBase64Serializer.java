package com.secret_message.secret_message_app.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Arrays;

/**
 * Writes a {@code byte[]} as Base64 directly to the JSON generator, then
 * zeroes the source array. This makes the serialization boundary the final
 * owner of key material (docs/MEMORY_HARDENING.md, milestone 3): the Base64
 * text exists only in Jackson's output buffer, never as an application-owned
 * {@link String}.
 *
 * <p>Jackson's internal output buffers are framework-owned copies we cannot
 * wipe; they are short-lived and recycled. The application-owned copy — the
 * annotated field — is destroyed here.
 */
public class WipingBase64Serializer extends JsonSerializer<byte[]> {

    @Override
    public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        try {
            gen.writeBinary(value, 0, value.length);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }
}
