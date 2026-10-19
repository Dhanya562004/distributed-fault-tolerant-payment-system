package com.paymentsystem.dto;

/**
 * Result of an outbound webhook HTTP delivery attempt.
 */
public class DeliveryResult {

    private final boolean success;
    private final boolean retryable;
    private final int statusCode;
    private final String responseBody;
    private final String errorMessage;

    private DeliveryResult(boolean success, boolean retryable, int statusCode, String responseBody, String errorMessage) {
        this.success = success;
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errorMessage = errorMessage;
    }

    public static DeliveryResult success(int statusCode, String responseBody) {
        return new DeliveryResult(true, false, statusCode, responseBody, null);
    }

    public static DeliveryResult retryableFailure(int statusCode, String errorMessage) {
        return new DeliveryResult(false, true, statusCode, null, errorMessage);
    }

    public static DeliveryResult permanentFailure(String errorMessage) {
        return new DeliveryResult(false, false, 400, null, errorMessage);
    }

    public static DeliveryResult permanentFailure(int statusCode, String errorMessage) {
        return new DeliveryResult(false, false, statusCode, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
