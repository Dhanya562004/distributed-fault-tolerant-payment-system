package com.paymentsystem.postgres;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.exception.PayloadMismatchException;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.IdempotencyService;
import com.paymentsystem.service.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL 16 Integration Tests for Idempotency Invariants and Duplicate Prevention.
 *
 * Verifies end-to-end ACID behavior against a real PostgreSQL container:
 * 1. New key acquisition and persistence.
 * 2. In-flight concurrent duplicate rejection (DuplicateRequestException).
 * 3. Cached idempotent replay without double deduction.
 * 4. Payload tampering detection (PayloadMismatchException).
 * 5. Concurrent duplicate request handling ensuring exactly one persisted payment and single balance deduction.
 */
public class PostgreSqlIdempotencyTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    @DisplayName("PostgreSQL: A new idempotency key can be acquired and payment processed successfully")
    void testAcquireNewKey_Success() {
        String userCode = "USR_PG_NEW_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_PG_NEW_" + UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.0000");
        BigDecimal paymentAmount = new BigDecimal("150.0000");

        userRepository.saveAndFlush(new User(userCode, "New PG User", userCode + "@pg.test", initialBalance));

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey, userCode, paymentAmount, "USD", "CREDIT_CARD", "PG Idempotency Initial Test"
        );

        PaymentResponse response = paymentProcessingService.initiatePayment(req);

        assertNotNull(response, "PaymentResponse should not be null");
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertFalse(Boolean.TRUE.equals(response.getIsCachedIdempotentResponse()), "First execution must not be cached");

        // Verify persisted PostgreSQL state
        Optional<IdempotencyKeyRecord> recordOpt = idempotencyKeyRepository.findByKeyValue(idempotencyKey);
        assertTrue(recordOpt.isPresent(), "Idempotency record must be persisted in PostgreSQL");
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, recordOpt.get().getStatus());
        assertEquals(200, recordOpt.get().getHttpStatusCode());
        assertNotNull(recordOpt.get().getResponseBody());

        List<PaymentTransaction> txList = paymentTransactionRepository.findAll().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .toList();
        assertEquals(1, txList.size(), "Exactly 1 payment transaction must be persisted in PostgreSQL");
        assertEquals(PaymentStatus.SUCCESS, txList.get(0).getStatus());

        User updatedUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("850.0000").compareTo(updatedUser.getBalance()), "Balance must be deducted by charge amount");
    }

    @Test
    @DisplayName("PostgreSQL: Reusing an IN_PROGRESS idempotency key produces expected DuplicateRequestException")
    void testReusingInProgressKey_ProducesConflict() {
        String key = "IDEM_PG_IN_PROGRESS_" + UUID.randomUUID();
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, "USR_DUMMY", new BigDecimal("100.00"), "USD", "CREDIT_CARD", "In-progress conflict test"
        );

        String hash = idempotencyService.computePayloadHash(req);
        IdempotencyKeyRecord inProgressRecord = new IdempotencyKeyRecord(key, hash);
        inProgressRecord.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(inProgressRecord);

        // Attempting to initiate or acquire must throw DuplicateRequestException
        assertThrows(DuplicateRequestException.class, () -> {
            paymentProcessingService.initiatePayment(req);
        }, "Reusing an IN_PROGRESS idempotency key in PostgreSQL must throw DuplicateRequestException");
    }

    @Test
    @DisplayName("PostgreSQL: A completed payment returns cached response without duplicate balance deduction")
    void testCompletedPayment_ReturnsCachedResponse() {
        String userCode = "USR_PG_CACHE_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_PG_CACHE_" + UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("2000.0000");
        BigDecimal paymentAmount = new BigDecimal("250.0000");

        userRepository.saveAndFlush(new User(userCode, "Cache User", userCode + "@pg.test", initialBalance));

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey, userCode, paymentAmount, "USD", "CREDIT_CARD", "Cached replay test"
        );

        // 1. Initial execution
        PaymentResponse response1 = paymentProcessingService.initiatePayment(req);
        assertNotNull(response1);
        assertEquals(PaymentStatus.SUCCESS, response1.getStatus());
        assertFalse(Boolean.TRUE.equals(response1.getIsCachedIdempotentResponse()));

        // Check balance after first charge
        User userAfterFirst = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("1750.0000").compareTo(userAfterFirst.getBalance()));

        // 2. Replay with identical key and payload
        PaymentResponse response2 = paymentProcessingService.initiatePayment(req);
        assertNotNull(response2);
        assertEquals(PaymentStatus.SUCCESS, response2.getStatus());
        assertEquals(response1.getPaymentId(), response2.getPaymentId(), "Payment ID must match original");
        assertTrue(Boolean.TRUE.equals(response2.getIsCachedIdempotentResponse()), "Replayed request must flag isCachedIdempotentResponse=true");

        // 3. Verify PostgreSQL balance was NOT deducted again
        User userAfterSecond = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("1750.0000").compareTo(userAfterSecond.getBalance()), "Balance must NOT decrease on cached replay!");

        // 4. Verify DB contains only 1 payment transaction
        long txCount = paymentTransactionRepository.findAll().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .count();
        assertEquals(1, txCount, "Database must contain exactly 1 transaction record!");
    }

    @Test
    @DisplayName("PostgreSQL: Reusing an idempotency key with different payload is rejected with PayloadMismatchException")
    void testReusingKeyWithDifferentPayload_RejectsWithConflict() {
        String userCode = "USR_PG_MISMATCH_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_PG_MISMATCH_" + UUID.randomUUID();

        userRepository.saveAndFlush(new User(userCode, "Mismatch User", userCode + "@pg.test", new BigDecimal("5000.0000")));

        InitiatePaymentRequest initialReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, new BigDecimal("100.0000"), "USD", "CREDIT_CARD", "Initial payment"
        );
        PaymentResponse firstResponse = paymentProcessingService.initiatePayment(initialReq);
        assertEquals(PaymentStatus.SUCCESS, firstResponse.getStatus());

        // Attempt same key with modified amount ($999.00 instead of $100.00)
        InitiatePaymentRequest conflictingReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, new BigDecimal("999.0000"), "USD", "CREDIT_CARD", "Tampered conflicting payload"
        );

        assertThrows(PayloadMismatchException.class, () -> {
            paymentProcessingService.initiatePayment(conflictingReq);
        }, "Reusing idempotency key with altered payload must be rejected with PayloadMismatchException");
    }

    @Test
    @DisplayName("PostgreSQL: Concurrent duplicate requests produce exactly one persisted payment and one balance deduction")
    void testConcurrentDuplicateRequests_PreventDoubleSpendingAndSinglePersistence() throws Exception {
        int threads = 20;
        String userCode = "USR_PG_CONC_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_PG_CONC_" + UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.0000");
        BigDecimal paymentAmount = new BigDecimal("100.0000");

        userRepository.saveAndFlush(new User(userCode, "Conc User", userCode + "@pg.test", initialBalance));

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        AtomicInteger originalCharges = new AtomicInteger(0);
        AtomicInteger cachedReplays = new AtomicInteger(0);
        AtomicInteger duplicateConflicts = new AtomicInteger(0);
        AtomicInteger unexpectedFailures = new AtomicInteger(0);

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey, userCode, paymentAmount, "USD", "CREDIT_CARD", "Concurrent PG duplicate test"
        );

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    PaymentResponse resp = paymentProcessingService.initiatePayment(req);
                    if (resp != null && resp.getStatus() == PaymentStatus.SUCCESS) {
                        if (Boolean.TRUE.equals(resp.getIsCachedIdempotentResponse())) {
                            cachedReplays.incrementAndGet();
                        } else {
                            originalCharges.incrementAndGet();
                        }
                    } else {
                        unexpectedFailures.incrementAndGet();
                    }
                } catch (DuplicateRequestException e) {
                    duplicateConflicts.incrementAndGet();
                } catch (Exception e) {
                    unexpectedFailures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = finishLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads must complete within timeout");
        assertEquals(1, originalCharges.get(), "Exactly ONE original charge must succeed in PostgreSQL");
        assertEquals(0, unexpectedFailures.get(), "Zero unexpected failures allowed");
        assertEquals(threads, originalCharges.get() + cachedReplays.get() + duplicateConflicts.get(), "All requests must be accounted for");

        // Verify PostgreSQL Database State
        List<PaymentTransaction> transactions = paymentTransactionRepository.findAll().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .toList();
        assertEquals(1, transactions.size(), "PostgreSQL must contain exactly 1 transaction row for the key");

        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("900.0000").compareTo(finalUser.getBalance()), "User balance in PostgreSQL must be deducted EXACTLY ONCE ($1000 -> $900)");

        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(idempotencyKey).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, record.getStatus());
        assertEquals(200, record.getHttpStatusCode());
    }
}
