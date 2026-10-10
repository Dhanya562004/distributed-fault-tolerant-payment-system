package com.paymentsystem.exception;

public class LockAcquisitionException extends RetryableException {
    public LockAcquisitionException(String message) {
        super(message);
    }
}
