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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;
    private final Clock clock;

    @Value("${app.idempotency.retention-hours:24}")
    private long retentionHours = 24;

    @Autowired
    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository,
                                  ObjectMapper objectMapper,
                                  MetricsService metricsService,
                                  Clock clock) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.clock = clock;
    }

    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository,
                                  ObjectMapper objectMapper,
                                  MetricsService metricsService) {
        this(idempotencyKeyRepository, objectMapper, metricsService, Clock.systemUTC());
    }

    public void setRetentionHours(long retentionHours) {
        this.retentionHours = retentionHours;
    }

    @Override
    public long getRetentionHours() {
        return retentionHours;
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

        Instant now = Instant.now(clock);
        String requestHash = computePayloadHash(payload);
        Optional<IdempotencyKeyRecord> existingOpt = idempotencyKeyRepository.findByKeyValue(keyValue);

        if (existingOpt.isPresent()) {
            IdempotencyKeyRecord existing = existingOpt.get();

            // 1. Check if the key has expired (based on server-side UTC time)
            if (existing.isExpired(now)) {
                return acquireExpiredKey(keyValue, requestHash, now);
            }

            // 2. Unexpired key: enforce payload match
            if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
                metricsService.recordIdempotencyHit();
                logger.warn("Idempotency key payload mismatch for key: {}", keyValue);
                throw new PayloadMismatchException("Request with idempotency key [" + keyValue + "] was previously executed with a different payload.");
            }

            // 3. Unexpired key: check in-progress vs completed
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

        // 4. Key does not exist: create brand new record
        Duration retention = Duration.ofHours(retentionHours);
        Instant expiresAt = now.plus(retention);
        IdempotencyKeyRecord newRecord = new IdempotencyKeyRecord(keyValue, requestHash, now, expiresAt);

        try {
            idempotencyKeyRepository.saveAndFlush(newRecord);
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            metricsService.recordIdempotencyHit();
            logger.warn("Race condition on idempotency key insertion for key: {}", keyValue);
            throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] was initiated concurrently.");
        }
    }

    /**
     * Atomically acquires and re-initializes an expired key for a new request.
     * Uses pessimistic locking (SELECT FOR UPDATE) to ensure simultaneous requests using the same expired key
     * cannot execute multiple charges.
     */
    private Optional<IdempotencyKeyRecord> acquireExpiredKey(String keyValue, String requestHash, Instant now) {
        Duration retention = Duration.ofHours(retentionHours);
        Instant newExpiresAt = now.plus(retention);

        int updated = idempotencyKeyRepository.updateIfExpired(
                keyValue, requestHash, now, newExpiresAt, IdempotencyKeyRecord.Status.IN_PROGRESS
        );

        if (updated > 0) {
            logger.info("Expired idempotency key [{}] atomically reused for new request (expires at {})", keyValue, newExpiresAt);
            return Optional.empty(); // newly acquired for execution
        }

        // Another thread won the atomic update race
        IdempotencyKeyRecord activeRecord = idempotencyKeyRepository.findByKeyValue(keyValue).orElse(null);
        if (activeRecord != null) {
            if (activeRecord.getRequestHash() != null && !activeRecord.getRequestHash().equals(requestHash)) {
                metricsService.recordIdempotencyHit();
                logger.warn("Idempotency key payload mismatch for key: {}", keyValue);
                throw new PayloadMismatchException("Request with idempotency key [" + keyValue + "] was previously executed with a different payload.");
            }
            if (activeRecord.getStatus() == IdempotencyKeyRecord.Status.IN_PROGRESS) {
                metricsService.recordIdempotencyHit();
                logger.warn("Concurrent duplicate request detected for key: {}", keyValue);
                throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] is currently being processed.");
            } else {
                metricsService.recordIdempotencyHit();
                logger.info("Idempotency key hit! Returning cached response for key: {}", keyValue);
                return Optional.of(activeRecord);
            }
        }

        // In the rare event the record was purged by cleanup concurrently
        IdempotencyKeyRecord newRecord = new IdempotencyKeyRecord(keyValue, requestHash, now, newExpiresAt);
        try {
            idempotencyKeyRepository.saveAndFlush(newRecord);
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            metricsService.recordIdempotencyHit();
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
                record.setUpdatedAt(Instant.now(clock));
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
            record.setUpdatedAt(Instant.now(clock));
            idempotencyKeyRepository.save(record);
        });
    }

    @Override
    @Transactional
    @Scheduled(cron = "${app.idempotency.cleanup-cron:0 0 * * * *}")
    public int cleanupExpiredKeys() {
        return cleanupExpiredKeys(Instant.now(clock));
    }

    @Override
    @Transactional
    public int cleanupExpiredKeys(Instant now) {
        int deleted = idempotencyKeyRepository.deleteExpiredKeysExcludingStatus(now, IdempotencyKeyRecord.Status.IN_PROGRESS);
        if (deleted > 0) {
            logger.info("Cleaned up {} expired idempotency keys before UTC {}", deleted, now);
        }
        return deleted;
    }
}
