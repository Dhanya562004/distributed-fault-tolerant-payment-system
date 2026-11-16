package com.paymentsystem.exception;

public class CumulativeRefundLimitExceededException extends RuntimeException {

    public CumulativeRefundLimitExceededException(String message) {
        super(message);
    }
}
