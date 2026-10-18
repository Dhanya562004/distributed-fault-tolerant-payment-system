package com.paymentsystem.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_key_value", columnList = "key_value", unique = true)
})
public class IdempotencyKeyRecord {

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_value", nullable = false, unique = true, length = 128)
    private String keyValue;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyKeyRecord() {
    }

    public IdempotencyKeyRecord(String keyValue, String requestHash) {
        this(keyValue, requestHash, Instant.now(), Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS));
    }

    public IdempotencyKeyRecord(String keyValue, String requestHash, Instant createdAt, Instant expiresAt) {
        this.keyValue = keyValue;
        this.requestHash = requestHash;
        this.status = Status.IN_PROGRESS;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = (this.createdAt != null) ? this.createdAt : Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.expiresAt == null) {
            this.expiresAt = now.plus(24, java.time.temporal.ChronoUnit.HOURS);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = (this.updatedAt != null) ? this.updatedAt : Instant.now();
    }

    public boolean isExpired(Instant currentUtcInstant) {
        if (this.expiresAt == null) {
            return false;
        }
        return !currentUtcInstant.isBefore(this.expiresAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
