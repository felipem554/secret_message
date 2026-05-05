package com.secret_message.secret_message_app.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("idempotency key reused with different body");
    }
}
