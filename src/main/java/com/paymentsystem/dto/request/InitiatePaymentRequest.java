package com.paymentsystem.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class InitiatePaymentRequest {

    private String idempotencyKey;

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    private String currency = "USD";

    private String paymentMethod = "CREDIT_CARD"; // CREDIT_CARD, DEBIT_CARD, UPI, BANK_TRANSFER, WALLET

    private String description;

    private Boolean asyncProcessing = false;

    public InitiatePaymentRequest() {
    }

    public InitiatePaymentRequest(String idempotencyKey, String userId, BigDecimal amount, String currency, String paymentMethod, String description) {
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
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
