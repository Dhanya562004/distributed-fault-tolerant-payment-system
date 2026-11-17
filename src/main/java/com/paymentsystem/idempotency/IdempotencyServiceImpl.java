package com.paymentsystem.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.idempotency.IdempotencyKeyRecord;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.exception.PayloadMismatchException;
import com.paymentsystem.idempotency.IdempotencyKeyRepository;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.security.RequestContext;
import com.paymentsystem.idempotency.IdempotencyService;
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
import java.util.List;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;
    private final Clock clock;
    private final PaymentTransactionRepository paymentRepository;

    @Value("${app.idempotency.retention-hours:24}")
    private long retentionHours = 24;

    @Value("${app.idempotency.in-progress-timeout-seconds:120}")
    private long inProgressTimeoutSeconds = 120;

    @Autowired
    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository,
                                  ObjectMapper objectMapper,
                                  MetricsService metricsService,
                                  Clock clock,
                                  @Autowired(required = false) PaymentTransactionRepository paymentRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.clock = clock;
        this.paymentRepository = paymentRepository;
    }

    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository,
                                  ObjectMapper objectMapper,
                                  MetricsService metricsService,
                                  Clock clock) {
        this(idempotencyKeyRepository, objectMapper, metricsService, clock, null);
    }

    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository,
                                  ObjectMapper objectMapper,
                                  MetricsService metricsService) {
        this(idempotencyKeyRepository, objectMapper, metricsService, Clock.systemUTC(), null);
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
        return tryAcquireOrGet(RequestContext.getApiKey(), keyValue, payload);
    }

    @Override
    @Transactional
    public Optional<IdempotencyKeyRecord> tryAcquireOrGet(String apiKey, String keyValue, Object payload) {
        if (keyValue == null || keyValue.trim().isEmpty()) {
            return Optional.empty();
        }

        String effectiveApiKey = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : "default";
        Instant now = Instant.now(clock);
        String requestHash = computePayloadHash(payload);
        Optional<IdempotencyKeyRecord> existingOpt = idempotencyKeyRepository.findByApiKeyAndKeyValue(effectiveApiKey, keyValue)
                .or(() -> "default".equals(effectiveApiKey) ? idempotencyKeyRepository.findByKeyValue(keyValue) : Optional.empty());

        if (existingOpt.isPresent()) {
            IdempotencyKeyRecord existing = existingOpt.get();

            // 1. Check if the key has expired (based on server-side UTC time)
            if (existing.isExpired(now)) {
                return acquireExpiredKey(effectiveApiKey, keyValue, requestHash, now);
            }

            // 2. Unexpired key: enforce payload match
            if (existing.getRequestHash() != null && !existing.getRequestHash().equals(requestHash)) {
                metricsService.recordIdempotencyHit();
                logger.warn("Idempotency key payload mismatch for key: {} (apiKey: {})", keyValue, effectiveApiKey);
                throw new PayloadMismatchException("Request with idempotency key [" + keyValue + "] was previously executed with a different payload.");
            }

            // 3. Unexpired key: check in-progress vs completed
            if (existing.getStatus() == IdempotencyKeyRecord.Status.IN_PROGRESS) {
                metricsService.recordIdempotencyHit();
                logger.warn("Concurrent duplicate request detected for key: {} (apiKey: {})", keyValue, effectiveApiKey);
                throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] is currently being processed.");
            } else {
                metricsService.recordIdempotencyHit();
                logger.info("Idempotency key hit! Returning cached response for key: {} (apiKey: {})", keyValue, effectiveApiKey);
                return Optional.of(existing);
            }
        }

        // 4. Key does not exist: create brand new record scoped to API key
        Duration retention = Duration.ofHours(retentionHours);
        Instant expiresAt = now.plus(retention);
        IdempotencyKeyRecord newRecord = new IdempotencyKeyRecord(effectiveApiKey, keyValue, requestHash, now, expiresAt);

        try {
            idempotencyKeyRepository.saveAndFlush(newRecord);
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            metricsService.recordIdempotencyHit();
            logger.warn("Race condition on idempotency key insertion for key: {} (apiKey: {})", keyValue, effectiveApiKey);
            throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] was initiated concurrently.");
        }
    }

    /**
     * Atomically acquires and re-initializes an expired key for a new request.
     * Scoped to the API key to prevent cross-customer interference.
     */
    private Optional<IdempotencyKeyRecord> acquireExpiredKey(String apiKey, String keyValue, String requestHash, Instant now) {
        Duration retention = Duration.ofHours(retentionHours);
        Instant newExpiresAt = now.plus(retention);

        int updated = idempotencyKeyRepository.updateIfExpiredScoped(
                apiKey, keyValue, requestHash, now, newExpiresAt, IdempotencyKeyRecord.Status.IN_PROGRESS
        );

        if (updated == 0 && "default".equals(apiKey)) {
            updated = idempotencyKeyRepository.updateIfExpired(
                    keyValue, requestHash, now, newExpiresAt, IdempotencyKeyRecord.Status.IN_PROGRESS
            );
        }

        if (updated > 0) {
            logger.info("Expired idempotency key [{}] (apiKey: {}) atomically reused for new request (expires at {})", keyValue, apiKey, newExpiresAt);
            return Optional.empty(); // newly acquired for execution
        }

        // Another thread won the atomic update race
        IdempotencyKeyRecord activeRecord = idempotencyKeyRepository.findByApiKeyAndKeyValue(apiKey, keyValue)
                .or(() -> "default".equals(apiKey) ? idempotencyKeyRepository.findByKeyValue(keyValue) : Optional.empty())
                .orElse(null);
        if (activeRecord != null) {
            if (activeRecord.getRequestHash() != null && !activeRecord.getRequestHash().equals(requestHash)) {
                metricsService.recordIdempotencyHit();
                logger.warn("Idempotency key payload mismatch for key: {} (apiKey: {})", keyValue, apiKey);
                throw new PayloadMismatchException("Request with idempotency key [" + keyValue + "] was previously executed with a different payload.");
            }
            if (activeRecord.getStatus() == IdempotencyKeyRecord.Status.IN_PROGRESS) {
                metricsService.recordIdempotencyHit();
                logger.warn("Concurrent duplicate request detected for key: {} (apiKey: {})", keyValue, apiKey);
                throw new DuplicateRequestException("Request with idempotency key [" + keyValue + "] is currently being processed.");
            } else {
                metricsService.recordIdempotencyHit();
                logger.info("Idempotency key hit! Returning cached response for key: {} (apiKey: {})", keyValue, apiKey);
                return Optional.of(activeRecord);
            }
        }

        // In the rare event the record was purged by cleanup concurrently
        IdempotencyKeyRecord newRecord = new IdempotencyKeyRecord(apiKey, keyValue, requestHash, now, newExpiresAt);
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
        markCompleted(RequestContext.getApiKey(), keyValue, responseObj, httpStatusCode);
    }

    @Override
    @Transactional
    public void markCompleted(String apiKey, String keyValue, Object responseObj, int httpStatusCode) {
        if (keyValue == null || keyValue.trim().isEmpty()) return;
        String effectiveApiKey = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : "default";

        Optional<IdempotencyKeyRecord> recordOpt = idempotencyKeyRepository.findByApiKeyAndKeyValue(effectiveApiKey, keyValue)
                .or(() -> "default".equals(effectiveApiKey) ? idempotencyKeyRepository.findByKeyValue(keyValue) : Optional.empty());

        recordOpt.ifPresent(record -> {
            try {
                record.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
                record.setResponseBody(objectMapper.writeValueAsString(responseObj));
                record.setHttpStatusCode(httpStatusCode);
                record.setUpdatedAt(Instant.now(clock));
                idempotencyKeyRepository.save(record);
                logger.debug("Idempotency key marked as COMPLETED: {} (apiKey: {})", keyValue, effectiveApiKey);
            } catch (Exception e) {
                logger.error("Failed to serialize response body for idempotency key: {}", keyValue, e);
            }
        });
    }

    @Override
    @Transactional
    public void markFailed(String keyValue, String errorMessage) {
        markFailed(RequestContext.getApiKey(), keyValue, errorMessage);
    }

    @Override
    @Transactional
    public void markFailed(String apiKey, String keyValue, String errorMessage) {
        if (keyValue == null || keyValue.trim().isEmpty()) return;
        String effectiveApiKey = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : "default";

        Optional<IdempotencyKeyRecord> recordOpt = idempotencyKeyRepository.findByApiKeyAndKeyValue(effectiveApiKey, keyValue)
                .or(() -> "default".equals(effectiveApiKey) ? idempotencyKeyRepository.findByKeyValue(keyValue) : Optional.empty());

        recordOpt.ifPresent(record -> {
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

    public void setInProgressTimeoutSeconds(long inProgressTimeoutSeconds) {
        this.inProgressTimeoutSeconds = inProgressTimeoutSeconds;
    }

    @Override
    public long getInProgressTimeoutSeconds() {
        return inProgressTimeoutSeconds;
    }

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${app.idempotency.recovery-job-interval-ms:60000}")
    public int recoverStuckKeys() {
        return recoverStuckKeys(Instant.now(clock), Duration.ofSeconds(inProgressTimeoutSeconds));
    }

    @Override
    @Transactional
    public int recoverStuckKeys(Instant now, Duration threshold) {
        Instant cutoff = now.minus(threshold);
        List<IdempotencyKeyRecord> stuckKeys = idempotencyKeyRepository.findStuckKeys(
                IdempotencyKeyRecord.Status.IN_PROGRESS, cutoff
        );

        if (stuckKeys.isEmpty()) {
            return 0;
        }

        int recoveredCount = 0;
        for (IdempotencyKeyRecord keyRecord : stuckKeys) {
            String keyValue = keyRecord.getKeyValue();
            Optional<PaymentTransaction> paymentOpt = paymentRepository != null ?
                    paymentRepository.findByIdempotencyKeyForUpdate(keyValue)
                            .or(() -> paymentRepository.findByIdempotencyKey(keyValue)) : Optional.empty();

            IdempotencyKeyRecord.Status targetStatus;
            String responseBody;
            int statusCode;

            if (paymentOpt.isPresent()) {
                PaymentTransaction tx = paymentOpt.get();
                if (tx.getStatus() == PaymentStatus.SUCCESS) {
                    PaymentResponse response = new PaymentResponse();
                    response.setPaymentId(tx.getPaymentId());
                    response.setIdempotencyKey(tx.getIdempotencyKey());
                    response.setUserId(tx.getUserId());
                    response.setAmount(tx.getAmount());
                    response.setCurrency(tx.getCurrency());
                    response.setStatus(tx.getStatus());
                    response.setPaymentMethod(tx.getPaymentMethod());
                    response.setDescription(tx.getDescription());
                    response.setFailureReason(tx.getFailureReason());
                    response.setRetryCount(tx.getRetryCount());
                    response.setCreatedAt(tx.getCreatedAt());
                    response.setUpdatedAt(tx.getUpdatedAt());
                    response.setIsCachedIdempotentResponse(true);

                    try {
                        responseBody = objectMapper.writeValueAsString(response);
                    } catch (Exception e) {
                        logger.error("Failed serializing recovered payment response for key: {}", keyValue, e);
                        responseBody = "{\"status\":\"SUCCESS\",\"paymentId\":\"" + tx.getPaymentId() + "\"}";
                    }
                    statusCode = 200;
                    targetStatus = IdempotencyKeyRecord.Status.COMPLETED;
                    logger.info("[IDEMPOTENCY RECOVERY] Found successful payment {} for stuck key [{}]. Marking COMPLETED.", tx.getPaymentId(), keyValue);
                } else if (tx.getStatus() == PaymentStatus.PROCESSING || tx.getStatus() == PaymentStatus.PENDING) {
                    // Transition stuck payment to FAILED without moving money
                    tx.transitionTo(PaymentStatus.FAILED);
                    tx.setFailureReason("Transaction recovery: operation abandoned or timed out while in progress");
                    paymentRepository.saveAndFlush(tx);

                    responseBody = "{\"error\":\"Transaction recovery: operation abandoned or timed out while in progress\"}";
                    statusCode = 500;
                    targetStatus = IdempotencyKeyRecord.Status.FAILED;
                    logger.warn("[IDEMPOTENCY RECOVERY] Payment {} in state {} for stuck key [{}]. Transitioned to FAILED.", tx.getPaymentId(), tx.getStatus(), keyValue);
                } else {
                    responseBody = "{\"error\":\"Transaction recovery: operation abandoned or timed out while in progress\"}";
                    statusCode = 500;
                    targetStatus = IdempotencyKeyRecord.Status.FAILED;
                    logger.warn("[IDEMPOTENCY RECOVERY] Payment {} in state {} for stuck key [{}]. Marking FAILED.", tx.getPaymentId(), tx.getStatus(), keyValue);
                }
            } else {
                responseBody = "{\"error\":\"Transaction recovery: operation abandoned or timed out while in progress\"}";
                statusCode = 500;
                targetStatus = IdempotencyKeyRecord.Status.FAILED;
                logger.warn("[IDEMPOTENCY RECOVERY] No payment transaction found for stuck key [{}]. Marking FAILED.", keyValue);
            }

            int updated = idempotencyKeyRepository.updateStuckKeyConditionally(
                    keyValue,
                    IdempotencyKeyRecord.Status.IN_PROGRESS,
                    targetStatus,
                    responseBody,
                    statusCode,
                    cutoff,
                    now
            );

            if (updated > 0) {
                recoveredCount++;
            }
        }

        // Also sweep any orphaned payments stuck in PENDING or PROCESSING older than threshold
        if (paymentRepository != null) {
            List<PaymentTransaction> stuckPayments = paymentRepository.findStuckPayments(
                    List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING), cutoff
            );
            for (PaymentTransaction p : stuckPayments) {
                Optional<PaymentTransaction> lockedOpt = paymentRepository.findByPaymentIdForUpdate(p.getPaymentId());
                if (lockedOpt.isPresent()) {
                    PaymentTransaction lockedTx = lockedOpt.get();
                    if ((lockedTx.getStatus() == PaymentStatus.PENDING || lockedTx.getStatus() == PaymentStatus.PROCESSING)
                            && lockedTx.getCreatedAt().isBefore(cutoff)) {
                        lockedTx.transitionTo(PaymentStatus.FAILED);
                        lockedTx.setFailureReason("Transaction recovery: operation abandoned or timed out while in progress");
                        paymentRepository.saveAndFlush(lockedTx);
                        logger.warn("[PAYMENT RECOVERY] Abandoned payment {} (status: {}) transitioned to FAILED.", lockedTx.getPaymentId(), p.getStatus());

                        if (lockedTx.getIdempotencyKey() != null) {
                            idempotencyKeyRepository.updateStuckKeyConditionally(
                                    lockedTx.getIdempotencyKey(),
                                    IdempotencyKeyRecord.Status.IN_PROGRESS,
                                    IdempotencyKeyRecord.Status.FAILED,
                                    "{\"error\":\"Transaction recovery: operation abandoned or timed out while in progress\"}",
                                    500,
                                    cutoff,
                                    now
                            );
                        }
                    }
                }
            }
        }

        return recoveredCount;
    }
}
