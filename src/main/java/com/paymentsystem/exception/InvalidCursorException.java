package com.paymentsystem.exception;

/**
 * Thrown when an opaque cursor cannot be parsed, has an invalid structure,
 * unsupported version, or fails HMAC cryptographic signature verification.
 */
public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message) {
        super(message);
    }

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
