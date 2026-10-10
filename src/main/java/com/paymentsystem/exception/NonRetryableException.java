package com.paymentsystem.exception;

public class NonRetryableException extends PaymentProcessingException {
    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
