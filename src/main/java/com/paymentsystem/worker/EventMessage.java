package com.paymentsystem.worker;

import java.math.BigDecimal;
import java.time.Instant;

public class EventMessage {

    private String eventId;
    private String paymentId;
    private String idempotencyKey;
    private String userId;
    private BigDecimal amount;
    private String currency;
    private String paymentMethod;
    private String description;
    private int attemptCount;
    private long nextRetryTimestamp;
    private Instant createdAt;

    public EventMessage() {
        this.createdAt = Instant.now();
        this.attemptCount = 0;
    }

    public EventMessage(String eventId, String paymentId, String idempotencyKey, String userId, BigDecimal amount, String currency, String paymentMethod, String description) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.description = description;
        this.attemptCount = 0;
        this.createdAt = Instant.now();
        this.nextRetryTimestamp = System.currentTimeMillis();
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
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

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public long getNextRetryTimestamp() {
        return nextRetryTimestamp;
    }

    public void setNextRetryTimestamp(long nextRetryTimestamp) {
        this.nextRetryTimestamp = nextRetryTimestamp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
