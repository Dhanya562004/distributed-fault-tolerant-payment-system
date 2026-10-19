package com.paymentsystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.WebhookEventPayload;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.RefundTransaction;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates transactional boundaries for payment transactions and outbox events.
 *
 * Guarantees that updating a payment or refund to terminal status and inserting its
 * corresponding outbox event execute within a single atomic database transaction.
 */
@Service
public class PaymentTransactionCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(PaymentTransactionCoordinator.class);

    private final PaymentTransactionRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${app.webhook.max-attempts:5}")
    private int defaultMaxAttempts = 5;

    @Autowired
    public PaymentTransactionCoordinator(
            PaymentTransactionRepository paymentRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public PaymentTransactionCoordinator(
            PaymentTransactionRepository paymentRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper) {
        this(paymentRepository, outboxEventRepository, objectMapper, Clock.systemUTC());
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    /**
     * Atomically updates a payment transaction to SUCCESS and persists a 'payment.succeeded' outbox event.
     */
    @Transactional
    public OutboxEvent commitPaymentSuccess(PaymentTransaction tx) {
        return commitPaymentSuccess(tx, this.defaultMaxAttempts);
    }

    @Transactional
    public OutboxEvent commitPaymentSuccess(PaymentTransaction tx, int maxAttempts) {
        Instant now = Instant.now(clock);
        tx.setStatus(PaymentStatus.SUCCESS);
        tx.setUpdatedAt(now);
        paymentRepository.saveAndFlush(tx);

        OutboxEvent event = buildPaymentOutboxEvent("payment.succeeded", tx, maxAttempts, now);
        OutboxEvent saved = outboxEventRepository.save(event);
        logger.info("[OUTBOX ATOMIC COMMIT] Committed payment.succeeded outbox event {} for payment {}", saved.getEventId(), tx.getPaymentId());
        return saved;
    }

    /**
     * Atomically updates a payment transaction to FAILED and persists a 'payment.failed' outbox event.
     */
    @Transactional
    public OutboxEvent commitPaymentFailure(PaymentTransaction tx, String failureReason) {
        return commitPaymentFailure(tx, failureReason, this.defaultMaxAttempts);
    }

    @Transactional
    public OutboxEvent commitPaymentFailure(PaymentTransaction tx, String failureReason, int maxAttempts) {
        Instant now = Instant.now(clock);
        tx.setStatus(PaymentStatus.FAILED);
        tx.setFailureReason(failureReason);
        tx.setUpdatedAt(now);
        paymentRepository.saveAndFlush(tx);

        OutboxEvent event = buildPaymentOutboxEvent("payment.failed", tx, maxAttempts, now);
        OutboxEvent saved = outboxEventRepository.save(event);
        logger.info("[OUTBOX ATOMIC COMMIT] Committed payment.failed outbox event {} for payment {}", saved.getEventId(), tx.getPaymentId());
        return saved;
    }

    /**
     * Atomically persists a 'refund.succeeded' outbox event within the active refund transaction boundary.
     */
    @Transactional
    public OutboxEvent commitRefundSuccess(RefundTransaction refund) {
        return commitRefundSuccess(refund, this.defaultMaxAttempts);
    }

    @Transactional
    public OutboxEvent commitRefundSuccess(RefundTransaction refund, int maxAttempts) {
        Instant now = Instant.now(clock);
        OutboxEvent event = buildRefundOutboxEvent("refund.succeeded", refund, maxAttempts, now);
        OutboxEvent saved = outboxEventRepository.save(event);
        logger.info("[OUTBOX ATOMIC COMMIT] Committed refund.succeeded outbox event {} for refund {}", saved.getEventId(), refund.getRefundId());
        return saved;
    }

    private OutboxEvent buildPaymentOutboxEvent(String eventType, PaymentTransaction tx, int maxAttempts, Instant now) {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", tx.getPaymentId());
        data.put("idempotencyKey", tx.getIdempotencyKey());
        data.put("userId", tx.getUserId());
        data.put("amount", tx.getAmount());
        data.put("currency", tx.getCurrency());
        data.put("status", tx.getStatus() != null ? tx.getStatus().name() : null);
        data.put("paymentMethod", tx.getPaymentMethod());
        data.put("description", tx.getDescription());
        data.put("failureReason", tx.getFailureReason());
        data.put("retryCount", tx.getRetryCount());
        data.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : now.toString());

        WebhookEventPayload payloadObj = new WebhookEventPayload(eventId, eventType, tx.getPaymentId(), now, data);
        String payloadJson = serializePayload(payloadObj);

        return new OutboxEvent(eventId, eventType, tx.getPaymentId(), payloadJson, maxAttempts, now);
    }

    private OutboxEvent buildRefundOutboxEvent(String eventType, RefundTransaction refund, int maxAttempts, Instant now) {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", refund.getRefundId());
        data.put("paymentId", refund.getPaymentId());
        data.put("userId", refund.getUserId());
        data.put("amount", refund.getAmount());
        data.put("status", refund.getStatus() != null ? refund.getStatus().name() : null);
        data.put("reason", refund.getReason());
        data.put("createdAt", refund.getCreatedAt() != null ? refund.getCreatedAt().toString() : now.toString());

        WebhookEventPayload payloadObj = new WebhookEventPayload(eventId, eventType, refund.getRefundId(), now, data);
        String payloadJson = serializePayload(payloadObj);

        return new OutboxEvent(eventId, eventType, refund.getRefundId(), payloadJson, maxAttempts, now);
    }

    private String serializePayload(WebhookEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize webhook event payload JSON for event {}", payload.getEventId(), e);
            throw new RuntimeException("Failed to serialize webhook event payload", e);
        }
    }
}
