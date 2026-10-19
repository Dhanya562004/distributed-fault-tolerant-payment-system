package com.paymentsystem.exception;

/**
 * Thrown when an authenticated caller lacks sufficient scope or supplies invalid credentials
 * to access protected financial transaction records.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
