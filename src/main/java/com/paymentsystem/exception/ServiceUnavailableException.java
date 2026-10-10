package com.paymentsystem.exception;

public class ServiceUnavailableException extends RetryableException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
