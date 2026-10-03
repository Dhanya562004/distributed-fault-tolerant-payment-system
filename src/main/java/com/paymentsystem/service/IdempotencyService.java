package com.paymentsystem.service;

import com.paymentsystem.entity.IdempotencyKeyRecord;

import java.util.Optional;

public interface IdempotencyService {
    String computePayloadHash(Object payload);
    Optional<IdempotencyKeyRecord> tryAcquireOrGet(String keyValue, Object payload);
    void markCompleted(String keyValue, Object responseObj, int httpStatusCode);
    void markFailed(String keyValue, String errorMessage);
}
