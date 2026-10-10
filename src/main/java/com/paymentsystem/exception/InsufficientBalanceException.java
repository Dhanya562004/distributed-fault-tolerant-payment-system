package com.paymentsystem.exception;

public class InsufficientBalanceException extends NonRetryableException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
