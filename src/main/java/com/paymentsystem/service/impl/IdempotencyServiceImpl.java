package com.paymentsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.exception.PayloadMismatchException;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.service.IdempotencyService;
import com.paymentsystem.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    @Autowired
    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository, ObjectMapper objectMapper, MetricsService metricsService) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Override
    public String computePayloadHash(Object payload) {
        try {
            String jsonStr = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(jsonStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            logger.warn("Could not hash payload object, using string representation", e);
            return Integer.toHexString(payload.hashCode());
        }
    }

    @Override
    @Transactional
    public Optional<IdempotencyKeyRecord> tryAcquireOrGet(String keyValue, Object payload) {
        if (keyValue == null || keyValue.trim().isEmpty()) {
            return Optional.empty();
        }

        String requestHash = computePayloadHash(payload);
        Optional<IdempotencyKeyRecord> existingOpt = idempotencyKeyRepository.findByKeyValue(keyValue);

        if (existingOpt.isPresent()) {
            IdempotencyKeyRecord existing = existingOpt.get();
            if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
                metricsService.recordIdempotencyHit();
                logger.warn("Idempotency key payload mismatch for key: {}", keyValue);
                throw new PayloadMismatchException("Request with idempotency key [" + keyValue + "] was previously executed with a different payload.");
            }
            if (existing.getStatus() == IdempotencyKeyRecord.Status.IN_PROGRESS) {
                metricsService.recordIdempotencyHit();
                logger.warn("Concurrent duplicate request detected for key: {}", keyValue);
                throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] is currently being processed.");
            } else {
                metricsService.recordIdempotencyHit();
                logger.info("Idempotency key hit! Returning cached response for key: {}", keyValue);
                return Optional.of(existing);
            }
        }

        IdempotencyKeyRecord newRecord = new IdempotencyKeyRecord(keyValue, requestHash);
        try {
            idempotencyKeyRepository.saveAndFlush(newRecord);
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            metricsService.recordIdempotencyHit();
            logger.warn("Race condition on idempotency key insertion for key: {}", keyValue);
            throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] was initiated concurrently.");
        }
    }

    @Override
    @Transactional
    public void markCompleted(String keyValue, Object responseObj, int httpStatusCode) {
        if (keyValue == null || keyValue.trim().isEmpty()) return;

        idempotencyKeyRepository.findByKeyValue(keyValue).ifPresent(record -> {
            try {
                record.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
                record.setResponseBody(objectMapper.writeValueAsString(responseObj));
                record.setHttpStatusCode(httpStatusCode);
                idempotencyKeyRepository.save(record);
                logger.debug("Idempotency key marked as COMPLETED: {}", keyValue);
            } catch (Exception e) {
                logger.error("Failed to serialize response body for idempotency key: {}", keyValue, e);
            }
        });
    }

    @Override
    @Transactional
    public void markFailed(String keyValue, String errorMessage) {
        if (keyValue == null || keyValue.trim().isEmpty()) return;

        idempotencyKeyRepository.findByKeyValue(keyValue).ifPresent(record -> {
            record.setStatus(IdempotencyKeyRecord.Status.FAILED);
            record.setResponseBody("{\"error\":\"" + errorMessage + "\"}");
            record.setHttpStatusCode(500);
            idempotencyKeyRepository.save(record);
        });
    }
}
