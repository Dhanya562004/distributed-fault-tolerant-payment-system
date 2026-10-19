package com.paymentsystem.exception;

/**
 * Thrown when an unauthenticated caller attempts to access protected pagination endpoints.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
