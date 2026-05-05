package com.secret_message.secret_message_app.exception;

public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(long maxBytes) {
        super("payload exceeds maximum size of " + maxBytes + " bytes");
    }
}
