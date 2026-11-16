package com.paymentsystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.WebhookEventPayload;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.RefundTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InsufficientBalanceException;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.security.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
    private final UserRepository userRepository;
    private final GatewaySimulationService gatewaySimulationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${app.webhook.max-attempts:5}")
    private int defaultMaxAttempts = 5;

    @Autowired
    public PaymentTransactionCoordinator(
            PaymentTransactionRepository paymentRepository,
            OutboxEventRepository outboxEventRepository,
            @Autowired(required = false) UserRepository userRepository,
            @Autowired(required = false) GatewaySimulationService gatewaySimulationService,
            ObjectMapper objectMapper,
            @Autowired(required = false) Clock clock) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.userRepository = userRepository;
        this.gatewaySimulationService = gatewaySimulationService;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public PaymentTransactionCoordinator(
            PaymentTransactionRepository paymentRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this(paymentRepository, outboxEventRepository, null, null, objectMapper, clock);
    }

    public PaymentTransactionCoordinator(
            PaymentTransactionRepository paymentRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper) {
        this(paymentRepository, outboxEventRepository, null, null, objectMapper, Clock.systemUTC());
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    /**
     * Atomically transitions a payment from PENDING to PROCESSING in its own isolated transaction.
     * Guaranteed to execute and commit before payment attempts begin, avoiding version mismatches on retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction commitPaymentProcessing(String paymentId) {
        Instant now = Instant.now(clock);
        PaymentTransaction tx = paymentRepository.findByPaymentIdForUpdate(paymentId)
                .or(() -> paymentRepository.findByPaymentId(paymentId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for ID: " + paymentId));

        if (tx.getStatus() == PaymentStatus.SUCCESS || tx.getStatus() == PaymentStatus.FAILED) {
            return tx;
        }

        tx.setStatus(PaymentStatus.PROCESSING);
        tx.setUpdatedAt(now);
        return paymentRepository.saveAndFlush(tx);
    }

    /**
     * Executes a single payment attempt atomically within ONE single database transaction:
     * 1. Locks payment row via SELECT FOR UPDATE and re-checks status (no double deductions).
     * 2. Locks user row and deducts balance.
     * 3. Simulates gateway execution (exceptions trigger clean automatic rollback of entire transaction).
     * 4. Updates payment status to SUCCESS.
     * 5. Persists outbox event within the SAME transactional boundary.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentTransaction executePaymentAttempt(String paymentId, InitiatePaymentRequest request) {
        Instant now = Instant.now(clock);

        // 1. Lock payment row with SELECT FOR UPDATE
        PaymentTransaction tx = paymentRepository.findByPaymentIdForUpdate(paymentId)
                .or(() -> paymentRepository.findByPaymentId(paymentId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for ID: " + paymentId));

        // 2. Status re-check: idempotent short-circuit if already processed
        if (tx.getStatus() == PaymentStatus.SUCCESS) {
            logger.info("Payment {} is already in SUCCESS state; skipping attempt and deduction", paymentId);
            return tx;
        }
        if (tx.getStatus() == PaymentStatus.FAILED) {
            throw new IllegalStateException("Payment " + paymentId + " has already FAILED; cannot retry");
        }

        // 3. Simulate DB interaction check
        if (gatewaySimulationService != null) {
            gatewaySimulationService.simulateDbInteraction(paymentId);
        }

        // 4. Lock user row and deduct balance
        if (userRepository != null) {
            Optional<User> userOpt = userRepository.findByUserCodeForUpdate(request.getUserId())
                    .or(() -> userRepository.findByUserCode(request.getUserId()));
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                if (user.getBalance() < request.getAmount()) {
                    throw new InsufficientBalanceException("Insufficient user account balance. Available: " + user.getBalance() + ", Requested: " + request.getAmount());
                }
                user.setBalance(user.getBalance() - request.getAmount());
                userRepository.save(user);
            } else {
                User newUser = new User(request.getUserId(), "Customer " + request.getUserId(), request.getUserId() + "@example.com", 1000000L - request.getAmount());
                userRepository.save(newUser);
            }
        }

        // 5. Simulate gateway call (if this fails, transaction rolls back - balance untouched, status unchanged)
        if (gatewaySimulationService != null) {
            gatewaySimulationService.simulateGatewayCall(paymentId, request.getPaymentMethod());
        }

        // 6. Update payment status to SUCCESS
        tx.setStatus(PaymentStatus.SUCCESS);
        tx.setUpdatedAt(now);
        PaymentTransaction savedTx = paymentRepository.saveAndFlush(tx);

        // 7. Persist outbox event in the same transaction
        if (outboxEventRepository != null) {
            OutboxEvent event = buildPaymentOutboxEvent("payment.succeeded", savedTx, this.defaultMaxAttempts, now);
            outboxEventRepository.save(event);
            logger.info("[ATOMIC PAYMENT COMMIT] Committed balance deduction, payment SUCCESS, and outbox event {} for payment {}", event.getEventId(), paymentId);
        }

        return savedTx;
    }

    /**
     * Atomically records a failed attempt's retryCount and failureReason in its own transaction.
     * Ensures rollbacks of the payment attempt do not lose the attempt counter or diagnostic errors.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction recordAttemptFailure(String paymentId, int currentAttempt, String failureReason) {
        Instant now = Instant.now(clock);
        PaymentTransaction tx = paymentRepository.findByPaymentIdForUpdate(paymentId)
                .or(() -> paymentRepository.findByPaymentId(paymentId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for ID: " + paymentId));

        if (tx.getStatus() == PaymentStatus.SUCCESS || tx.getStatus() == PaymentStatus.FAILED) {
            return tx;
        }

        tx.setRetryCount(currentAttempt);
        tx.setFailureReason(failureReason);
        tx.setUpdatedAt(now);
        return paymentRepository.saveAndFlush(tx);
    }

    /**
     * Atomically marks a payment as FAILED by ID in its own transaction and creates the payment.failed outbox event.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction commitPaymentFailure(String paymentId, String failureReason, int retryCount) {
        return commitPaymentFailure(paymentId, failureReason, retryCount, this.defaultMaxAttempts);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction commitPaymentFailure(String paymentId, String failureReason, int retryCount, int maxAttempts) {
        Instant now = Instant.now(clock);
        PaymentTransaction tx = paymentRepository.findByPaymentIdForUpdate(paymentId)
                .or(() -> paymentRepository.findByPaymentId(paymentId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found for ID: " + paymentId));

        if (tx.getStatus() == PaymentStatus.SUCCESS) {
            logger.warn("Cannot mark payment {} as FAILED because it is already SUCCESS", paymentId);
            return tx;
        }

        tx.setStatus(PaymentStatus.FAILED);
        tx.setFailureReason(failureReason);
        tx.setRetryCount(retryCount);
        tx.setUpdatedAt(now);
        PaymentTransaction savedTx = paymentRepository.saveAndFlush(tx);

        if (outboxEventRepository != null) {
            OutboxEvent event = buildPaymentOutboxEvent("payment.failed", savedTx, maxAttempts, now);
            outboxEventRepository.save(event);
            logger.info("[OUTBOX ATOMIC COMMIT] Committed payment.failed outbox event {} for payment {}", event.getEventId(), paymentId);
        }
        return savedTx;
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
        String requestId = tx.getRequestId() != null ? tx.getRequestId() : RequestContext.getRequestId();

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
        data.put("requestId", requestId);
        data.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : now.toString());

        WebhookEventPayload payloadObj = new WebhookEventPayload(eventId, eventType, tx.getPaymentId(), now, requestId, data);
        String payloadJson = serializePayload(payloadObj);

        OutboxEvent event = new OutboxEvent(eventId, eventType, tx.getPaymentId(), payloadJson, maxAttempts, now);
        event.setRequestId(requestId);
        return event;
    }

    private OutboxEvent buildRefundOutboxEvent(String eventType, RefundTransaction refund, int maxAttempts, Instant now) {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String requestId = RequestContext.getRequestId();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", refund.getRefundId());
        data.put("paymentId", refund.getPaymentId());
        data.put("userId", refund.getUserId());
        data.put("amount", refund.getAmount());
        data.put("status", refund.getStatus() != null ? refund.getStatus().name() : null);
        data.put("reason", refund.getReason());
        data.put("requestId", requestId);
        data.put("createdAt", refund.getCreatedAt() != null ? refund.getCreatedAt().toString() : now.toString());

        WebhookEventPayload payloadObj = new WebhookEventPayload(eventId, eventType, refund.getRefundId(), now, requestId, data);
        String payloadJson = serializePayload(payloadObj);

        OutboxEvent event = new OutboxEvent(eventId, eventType, refund.getRefundId(), payloadJson, maxAttempts, now);
        event.setRequestId(requestId);
        return event;
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
