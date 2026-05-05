package com.secret_message.secret_message_app.exception;

/**
 * Thrown for any reveal-failure case that the API surfaces as a uniform 404:
 * message not found, wrong key, or attempts exhausted. The single-exception
 * design makes it impossible for the controller to accidentally distinguish
 * these cases externally — a security property the 3-strike counter depends on.
 */
public class MessageNotAvailableException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        WRONG_KEY,
        EXHAUSTED,
        RACE_LOST
    }

    private final Reason reason;

    public MessageNotAvailableException(Reason reason) {
        super("message not available");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
