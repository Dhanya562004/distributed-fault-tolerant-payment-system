package com.paymentsystem.exception;

public class PayloadMismatchException extends NonRetryableException {
    public PayloadMismatchException(String message) {
        super(message);
    }
}
