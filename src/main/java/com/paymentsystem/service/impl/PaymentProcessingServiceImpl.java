package com.paymentsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InsufficientBalanceException;
import com.paymentsystem.exception.NonRetryableException;
import com.paymentsystem.exception.PaymentProcessingException;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.exception.RetryableException;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.AuditService;
import com.paymentsystem.service.GatewaySimulationService;
import com.paymentsystem.service.IdempotencyService;
import com.paymentsystem.service.MetricsService;
import com.paymentsystem.service.PaymentProcessingService;
import com.paymentsystem.worker.DistributedQueueManager;
import com.paymentsystem.worker.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PaymentProcessingServiceImpl implements PaymentProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentProcessingServiceImpl.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final PaymentTransactionRepository paymentRepository;
    private final UserRepository userRepository;
    private final IdempotencyService idempotencyService;
    private final GatewaySimulationService gatewaySimulationService;
    private final AuditService auditService;
    private final MetricsService metricsService;
    private final DistributedQueueManager queueManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public PaymentProcessingServiceImpl(PaymentTransactionRepository paymentRepository,
                                        UserRepository userRepository,
                                        IdempotencyService idempotencyService,
                                        GatewaySimulationService gatewaySimulationService,
                                        AuditService auditService,
                                        MetricsService metricsService,
                                        DistributedQueueManager queueManager,
                                        ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.idempotencyService = idempotencyService;
        this.gatewaySimulationService = gatewaySimulationService;
        this.auditService = auditService;
        this.metricsService = metricsService;
        this.queueManager = queueManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentResponse initiatePayment(InitiatePaymentRequest request) {
        long startTime = System.currentTimeMillis();
        metricsService.incrementTotalTransactions();

        Optional<IdempotencyKeyRecord> cachedOpt = idempotencyService.tryAcquireOrGet(request.getIdempotencyKey(), request);
        if (cachedOpt.isPresent()) {
            IdempotencyKeyRecord record = cachedOpt.get();
            try {
                PaymentResponse response = objectMapper.readValue(record.getResponseBody(), PaymentResponse.class);
                response.setIsCachedIdempotentResponse(true);
                return response;
            } catch (Exception e) {
                logger.error("Failed parsing cached response JSON for key: {}", request.getIdempotencyKey(), e);
            }
        }

        String paymentId = "PAY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        if (Boolean.TRUE.equals(request.getAsyncProcessing())) {
            return processAsyncPayment(request, paymentId);
        } else {
            return processSyncPaymentWithRetries(request, paymentId, startTime);
        }
    }

    private PaymentResponse processAsyncPayment(InitiatePaymentRequest request, String paymentId) {
        PaymentTransaction tx = new PaymentTransaction(
                paymentId,
                request.getIdempotencyKey(),
                request.getUserId(),
                request.getAmount(),
                request.getCurrency(),
                request.getPaymentMethod(),
                request.getDescription()
        );
        tx.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.saveAndFlush(tx);

        auditService.recordAuditLog(
                paymentId, request.getIdempotencyKey(), request.getUserId(),
                "NONE", "PROCESSING", "SUBMIT_ASYNC_QUEUE", "API_HANDLER",
                "Payment submitted to partition queue for background worker processing", Map.of()
        );

        EventMessage message = new EventMessage(
                "EVT_" + UUID.randomUUID().toString().substring(0, 8),
                paymentId,
                request.getIdempotencyKey(),
                request.getUserId(),
                request.getAmount(),
                request.getCurrency(),
                request.getPaymentMethod(),
                request.getDescription()
        );

        queueManager.publish(request.getUserId(), message);

        PaymentResponse response = mapToResponse(tx);
        response.setIsCachedIdempotentResponse(false);
        return response;
    }

    private PaymentResponse processSyncPaymentWithRetries(InitiatePaymentRequest request, String paymentId, long startTime) {
        PaymentTransaction tx = new PaymentTransaction(
                paymentId,
                request.getIdempotencyKey(),
                request.getUserId(),
                request.getAmount(),
                request.getCurrency(),
                request.getPaymentMethod(),
                request.getDescription()
        );
        tx.setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(tx);

        auditService.recordAuditLog(
                paymentId, request.getIdempotencyKey(), request.getUserId(),
                "NONE", "PENDING", "INITIATE_SYNC_PAYMENT", "API_HANDLER",
                "Initiating synchronous payment processing", Map.of()
        );

        int attempts = 0;
        long backoffMs = 500;
        boolean success = false;
        String lastError = null;

        while (attempts < MAX_RETRY_ATTEMPTS && !success) {
            attempts++;
            try {
                if (attempts > 1) {
                    metricsService.recordRetry();
                    logger.info("[RETRY ATTEMPT {}/{}] Retrying payment {} with backoff {}ms", attempts, MAX_RETRY_ATTEMPTS, paymentId, backoffMs);
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min((long) (backoffMs * 2.0 + (Math.random() * 200)), 8000);
                }

                executeSinglePaymentAttempt(tx, request);
                success = true;

            } catch (NonRetryableException ex) {
                lastError = ex.getMessage();
                logger.warn("[NON-RETRYABLE FAILURE] Payment {} failed fast with business error: {}", paymentId, ex.getMessage());
                break;
            } catch (RetryableException ex) {
                lastError = ex.getMessage();
                logger.warn("[ATTEMPT {}/{}] Retryable payment failure for {}: {}", attempts, MAX_RETRY_ATTEMPTS, paymentId, ex.getMessage());
            } catch (PaymentProcessingException ex) {
                lastError = ex.getMessage();
                logger.warn("[ATTEMPT {}/{}] Payment processing failure for {}: {}", attempts, MAX_RETRY_ATTEMPTS, paymentId, ex.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "Execution interrupted";
                break;
            } catch (Exception ex) {
                lastError = ex.getMessage();
                logger.error("Unexpected error during attempt {} for payment {}: {}", attempts, paymentId, ex.getMessage(), ex);
            }
        }

        tx.setRetryCount(attempts - 1);
        long latencyMs = System.currentTimeMillis() - startTime;
        metricsService.recordLatency(latencyMs);

        if (success) {
            tx.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.saveAndFlush(tx);
            metricsService.incrementSuccessfulTransactions();

            auditService.recordAuditLog(
                    paymentId, request.getIdempotencyKey(), request.getUserId(),
                    "PENDING", "SUCCESS", "PAYMENT_AUTHORIZED", "WORKER_DIRECT",
                    "Payment successfully authorized after " + attempts + " attempt(s)", Map.of("latencyMs", latencyMs)
            );

            PaymentResponse response = mapToResponse(tx);
            idempotencyService.markCompleted(request.getIdempotencyKey(), response, 200);
            return response;
        } else {
            tx.setStatus(PaymentStatus.FAILED);
            tx.setFailureReason(lastError);
            paymentRepository.saveAndFlush(tx);
            metricsService.incrementFailedTransactions();

            auditService.recordAuditLog(
                    paymentId, request.getIdempotencyKey(), request.getUserId(),
                    "PENDING", "FAILED", "PAYMENT_REJECTED", "WORKER_DIRECT",
                    "Payment failed after " + attempts + " attempt(s). Reason: " + lastError, Map.of("retryCount", attempts - 1)
            );

            PaymentResponse response = mapToResponse(tx);
            idempotencyService.markCompleted(request.getIdempotencyKey(), response, 500);
            return response;
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void executeSinglePaymentAttempt(PaymentTransaction tx, InitiatePaymentRequest request) {
        gatewaySimulationService.simulateDbInteraction(tx.getPaymentId());

        Optional<User> userOpt = userRepository.findByUserCode(request.getUserId());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (user.getBalance().compareTo(request.getAmount()) < 0) {
                throw new InsufficientBalanceException("Insufficient user account balance. Available: " + user.getBalance() + ", Requested: " + request.getAmount());
            }
            user.setBalance(user.getBalance().subtract(request.getAmount()));
            userRepository.save(user);
        } else {
            User newUser = new User(request.getUserId(), "Customer " + request.getUserId(), request.getUserId() + "@example.com", new BigDecimal("10000.00").subtract(request.getAmount()));
            userRepository.save(newUser);
        }

        gatewaySimulationService.simulateGatewayCall(tx.getPaymentId(), request.getPaymentMethod());
    }

    @Override
    public boolean processWorkerMessage(EventMessage message, String workerId) {
        logger.info("[WORKER {}] Processing event {} for payment {}", workerId, message.getEventId(), message.getPaymentId());
        Optional<PaymentTransaction> txOpt = paymentRepository.findByPaymentId(message.getPaymentId());
        if (txOpt.isEmpty()) {
            logger.error("Worker could not find transaction record for paymentId: {}", message.getPaymentId());
            return false;
        }

        PaymentTransaction tx = txOpt.get();
        if (tx.getStatus() == PaymentStatus.SUCCESS || tx.getStatus() == PaymentStatus.FAILED) {
            logger.info("Payment {} is already in terminal state {}, skipping worker duplicate execution", tx.getPaymentId(), tx.getStatus());
            return true;
        }

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                message.getIdempotencyKey(),
                message.getUserId(),
                message.getAmount(),
                message.getCurrency(),
                message.getPaymentMethod(),
                message.getDescription()
        );

        try {
            executeSinglePaymentAttempt(tx, req);
            tx.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.saveAndFlush(tx);
            metricsService.incrementSuccessfulTransactions();

            auditService.recordAuditLog(
                    tx.getPaymentId(), tx.getIdempotencyKey(), tx.getUserId(),
                    "PROCESSING", "SUCCESS", "WORKER_PAYMENT_SUCCESS", workerId,
                    "Payment authorized by worker pool", Map.of()
            );

            PaymentResponse response = mapToResponse(tx);
            idempotencyService.markCompleted(tx.getIdempotencyKey(), response, 200);
            return true;

        } catch (PaymentProcessingException ex) {
            logger.warn("[WORKER {}] Payment processing exception for {}: {}", workerId, tx.getPaymentId(), ex.getMessage());
            tx.setFailureReason(ex.getMessage());
            paymentRepository.saveAndFlush(tx);
            return false;
        } catch (Exception ex) {
            logger.error("[WORKER {}] Unexpected failure processing payment {}: {}", workerId, tx.getPaymentId(), ex.getMessage(), ex);
            tx.setFailureReason(ex.getMessage());
            paymentRepository.saveAndFlush(tx);
            return false;
        }
    }

    @Override
    public PaymentResponse getPaymentStatus(String paymentId) {
        PaymentTransaction tx = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with ID: " + paymentId));
        return mapToResponse(tx);
    }

    @Override
    public List<PaymentResponse> getAllPayments() {
        return getAllPayments(0, 50);
    }

    @Override
    public List<PaymentResponse> getAllPayments(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = (size <= 0 || size > 100) ? 20 : size;
        return paymentRepository.findAll().stream()
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentResponse> getPaymentsByUser(String userId) {
        return getPaymentsByUser(userId, 0, 50);
    }

    @Override
    public List<PaymentResponse> getPaymentsByUser(String userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = (size <= 0 || size > 100) ? 20 : size;
        return paymentRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public PaymentResponse mapToResponse(PaymentTransaction tx) {
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
        response.setIsCachedIdempotentResponse(false);
        return response;
    }
}
