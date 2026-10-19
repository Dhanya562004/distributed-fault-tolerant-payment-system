package com.paymentsystem.dto.response;

import com.paymentsystem.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public response projection for payment transactions in cursor-based pagination.
 * Excludes sensitive internal fields such as lockVersion, HMAC signing keys,
 * internal exception stack traces, and idempotency request payload dumps.
 */
public class PaymentTransactionResponse {

    private String id;
    private String paymentId;
    private String userId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private String paymentMethod;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;

    public PaymentTransactionResponse() {
    }

    public PaymentTransactionResponse(String id, String paymentId, String userId, BigDecimal amount,
                                      String currency, PaymentStatus status, String paymentMethod,
                                      String description, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
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

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
