package com.paymentsystem.exception;

public class GatewayTimeoutException extends RetryableException {
    public GatewayTimeoutException(String message) {
        super(message);
    }
}
