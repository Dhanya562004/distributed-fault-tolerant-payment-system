package com.paymentsystem.service;

import com.paymentsystem.entity.IdempotencyKeyRecord;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyService {
    String computePayloadHash(Object payload);
    Optional<IdempotencyKeyRecord> tryAcquireOrGet(String keyValue, Object payload);
    Optional<IdempotencyKeyRecord> tryAcquireOrGet(String apiKey, String keyValue, Object payload);
    void markCompleted(String keyValue, Object responseObj, int httpStatusCode);
    void markCompleted(String apiKey, String keyValue, Object responseObj, int httpStatusCode);
    void markFailed(String keyValue, String errorMessage);
    void markFailed(String apiKey, String keyValue, String errorMessage);
    int cleanupExpiredKeys();
    int cleanupExpiredKeys(Instant now);
    int recoverStuckKeys();
    int recoverStuckKeys(Instant now, java.time.Duration threshold);
    long getRetentionHours();
    long getInProgressTimeoutSeconds();
}
