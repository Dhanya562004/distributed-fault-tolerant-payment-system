package com.paymentsystem.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InitiatePaymentRequest {

    private String idempotencyKey;

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Amount must be greater than zero")
    private Long amount;

    @NotBlank(message = "Currency is required")
    private String currency = "USD";

    private String paymentMethod = "CREDIT_CARD"; // CREDIT_CARD, DEBIT_CARD, UPI, BANK_TRANSFER, WALLET

    private String description;

    private Boolean asyncProcessing = false;

    public InitiatePaymentRequest() {
    }

    public InitiatePaymentRequest(String idempotencyKey, String userId, Long amount, String currency, String paymentMethod, String description) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.description = description;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        if (currency != null && !currency.trim().isEmpty()) {
            this.currency = currency.trim().toUpperCase();
        } else {
            this.currency = "USD";
        }
    }

    public String getPaymentMethod() {
        return paymentMethod != null ? paymentMethod : "CREDIT_CARD";
    }

    public void setPaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.trim().isEmpty()) {
            this.paymentMethod = "CREDIT_CARD";
        } else {
            String upper = paymentMethod.trim().toUpperCase();
            if ("CARD".equals(upper)) {
                this.paymentMethod = "CREDIT_CARD";
            } else {
                this.paymentMethod = upper;
            }
        }
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getAsyncProcessing() {
        return asyncProcessing;
    }

    public void setAsyncProcessing(Boolean asyncProcessing) {
        this.asyncProcessing = asyncProcessing;
    }
}
