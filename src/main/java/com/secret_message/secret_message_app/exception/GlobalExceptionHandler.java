package com.secret_message.secret_message_app.exception;

import com.secret_message.secret_message_app.dto.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private static final String CACHE_NO_STORE = "no-store";

    private final MeterRegistry meterRegistry;

    /**
     * All reveal-failure cases return identical 404 externally.
     * Internal Micrometer counters track the reason so operators can
     * distinguish brute-force attempts from expiry / not-found without
     * exposing the difference to callers.
     */
    @ExceptionHandler(MessageNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotAvailable(MessageNotAvailableException ex) {
        meterRegistry.counter("reveal.failed",
                "reason", ex.getReason().name().toLowerCase()).increment();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Cache-Control", CACHE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("message not available"));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Cache-Control", CACHE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("idempotency key conflict"));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .header("Cache-Control", CACHE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("payload too large"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Cache-Control", CACHE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Cache-Control", CACHE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("invalid request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", CACHE_NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("service unavailable"));
    }
}
