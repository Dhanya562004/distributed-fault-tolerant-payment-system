package com.paymentsystem.exception;

/**
 * Thrown when pagination query parameters (e.g. limit, status, date range)
 * violate API validation constraints or conflict with cursor filter bindings.
 */
public class InvalidPaginationFilterException extends RuntimeException {
    public InvalidPaginationFilterException(String message) {
        super(message);
    }

    public InvalidPaginationFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}
