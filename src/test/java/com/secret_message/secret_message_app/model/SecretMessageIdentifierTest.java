package com.secret_message.secret_message_app.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SecretMessageIdentifierTest {

    @Test
    void toString_neverContainsKeyMaterial() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 42);
        SecretMessageIdentifier id = new SecretMessageIdentifier("msg-1", key);

        String rendered = id.toString();

        assertFalse(rendered.contains("42"),
                "toString() must not render AES key bytes (Lombok renders array contents unless excluded)");
        assertTrue(rendered.contains("msg-1"), "messageId should still be present");
    }

    @Test
    void wipe_zeroesKeyBytes() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        SecretMessageIdentifier id = new SecretMessageIdentifier("msg-2", key);

        id.wipe();

        byte[] zeros = new byte[32];
        assertArrayEquals(zeros, id.getAeskey(), "wipe() must zero the key buffer in place");
    }

    @Test
    void wipe_onNullKey_doesNotThrow() {
        SecretMessageIdentifier id = new SecretMessageIdentifier();
        assertDoesNotThrow(id::wipe);
    }
}
