package com.paymentsystem.postgres;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.idempotency.IdempotencyKeyRecord;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.exception.PayloadMismatchException;
import com.paymentsystem.idempotency.IdempotencyKeyRepository;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.idempotency.IdempotencyService;
import com.paymentsystem.payment.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
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
        Long initialBalance = 100000L;
        Long paymentAmount = 15000L;

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
        assertEquals(85000L, updatedUser.getBalance(), "Balance must be deducted by charge amount");
    }

    @Test
    @DisplayName("PostgreSQL: Reusing an IN_PROGRESS idempotency key produces expected DuplicateRequestException")
    void testReusingInProgressKey_ProducesConflict() {
        String key = "IDEM_PG_IN_PROGRESS_" + UUID.randomUUID();
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, "USR_DUMMY", 10000L, "USD", "CREDIT_CARD", "In-progress conflict test"
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
        Long initialBalance = 200000L;
        Long paymentAmount = 25000L;

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
        assertEquals(175000L, userAfterFirst.getBalance());

        // 2. Replay with identical key and payload
        PaymentResponse response2 = paymentProcessingService.initiatePayment(req);
        assertNotNull(response2);
        assertEquals(PaymentStatus.SUCCESS, response2.getStatus());
        assertEquals(response1.getPaymentId(), response2.getPaymentId(), "Payment ID must match original");
        assertTrue(Boolean.TRUE.equals(response2.getIsCachedIdempotentResponse()), "Replayed request must flag isCachedIdempotentResponse=true");

        // 3. Verify PostgreSQL balance was NOT deducted again
        User userAfterSecond = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(175000L, userAfterSecond.getBalance(), "Balance must NOT decrease on cached replay!");

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

        userRepository.saveAndFlush(new User(userCode, "Mismatch User", userCode + "@pg.test", 500000L));

        InitiatePaymentRequest initialReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 10000L, "USD", "CREDIT_CARD", "Initial payment"
        );
        PaymentResponse firstResponse = paymentProcessingService.initiatePayment(initialReq);
        assertEquals(PaymentStatus.SUCCESS, firstResponse.getStatus());

        // Attempt same key with modified amount ($999.00 instead of $100.00)
        InitiatePaymentRequest conflictingReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 99900L, "USD", "CREDIT_CARD", "Tampered conflicting payload"
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
        Long initialBalance = 100000L;
        Long paymentAmount = 10000L;

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
        assertEquals(90000L, finalUser.getBalance(), "User balance in PostgreSQL must be deducted EXACTLY ONCE ($1000 -> $900)");

        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(idempotencyKey).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, record.getStatus());
        assertEquals(200, record.getHttpStatusCode());
    }

    @Test
    @DisplayName("PostgreSQL: Expired key can be reused to create a brand new payment")
    void testPostgreSqlExpiredKeyReuse_AllowsNewPayment() {
        String userCode = "USR_PG_EXP_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "IDEM_PG_EXP_" + System.nanoTime();

        userRepository.save(new User(userCode, "PG Expiry User", "pgexp@apexpay.com", 100000L));

        // 1. Initial Payment
        InitiatePaymentRequest req1 = new InitiatePaymentRequest(
                idempotencyKey, userCode, 10000L, "USD", "CREDIT_CARD", "Initial PG charge"
        );
        PaymentResponse resp1 = paymentProcessingService.initiatePayment(req1);
        assertNotNull(resp1.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, resp1.getStatus());

        // 2. Mark key as expired in PostgreSQL (simulate expiry after 24h retention period)
        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(idempotencyKey).orElseThrow();
        record.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        idempotencyKeyRepository.saveAndFlush(record);

        // 3. Reuse the expired key with a new payment request
        InitiatePaymentRequest req2 = new InitiatePaymentRequest(
                idempotencyKey, userCode, 25000L, "USD", "CREDIT_CARD", "Subsequent PG charge after expiry"
        );
        PaymentResponse resp2 = paymentProcessingService.initiatePayment(req2);

        assertNotNull(resp2.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, resp2.getStatus());
        assertNotEquals(resp1.getPaymentId(), resp2.getPaymentId(), "New payment ID must be assigned");
        assertFalse(Boolean.TRUE.equals(resp2.getIsCachedIdempotentResponse()), "Fresh execution must not be marked as cached");

        // Verify balance in PostgreSQL: $1000 - $100 - $250 = $650
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(65000L, finalUser.getBalance());

        // Exactly 2 transactions exist in PostgreSQL
        List<PaymentTransaction> txs = paymentTransactionRepository.findAll().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .toList();
        assertEquals(2, txs.size(), "Both payments must be persisted in PostgreSQL");
    }

    @Test
    @DisplayName("PostgreSQL: Concurrent requests reusing expired key prevent double charges via SELECT FOR UPDATE")
    void testPostgreSqlConcurrentExpiredKeyReuse_PreventsDoubleCharge() throws Exception {
        String userCode = "USR_PG_CEXP_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "IDEM_PG_CEXP_" + System.nanoTime();

        userRepository.save(new User(userCode, "PG Concur Expiry User", "pgcexp@apexpay.com", 100000L));

        // 1. Initial payment
        InitiatePaymentRequest initialReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 10000L, "USD", "CREDIT_CARD", "Day 1 payment"
        );
        PaymentResponse initialResp = paymentProcessingService.initiatePayment(initialReq);
        assertNotNull(initialResp.getPaymentId());

        // 2. Mark key as expired in PostgreSQL
        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(idempotencyKey).orElseThrow();
        record.setExpiresAt(Instant.now().minus(Duration.ofHours(1)));
        idempotencyKeyRepository.saveAndFlush(record);

        // 3. Launch 15 concurrent threads in PostgreSQL attempting to reuse the expired key
        int threads = 15;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        AtomicInteger originalCharges = new AtomicInteger(0);
        AtomicInteger cachedReplays = new AtomicInteger(0);
        AtomicInteger duplicateConflicts = new AtomicInteger(0);
        AtomicInteger unexpectedFailures = new AtomicInteger(0);

        InitiatePaymentRequest reuseReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 20000L, "USD", "CREDIT_CARD", "Day 2 concurrent reuse"
        );

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    PaymentResponse resp = paymentProcessingService.initiatePayment(reuseReq);
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

        assertTrue(completed);
        assertEquals(1, originalCharges.get(), "Exactly ONE original charge must succeed when reusing expired key in PostgreSQL");
        assertEquals(0, unexpectedFailures.get(), "Zero unexpected failures allowed");
        assertEquals(threads, originalCharges.get() + cachedReplays.get() + duplicateConflicts.get());

        // Initial $100 + Reused $200 = $300 deducted. Final balance in PostgreSQL: $700
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(70000L, finalUser.getBalance());

        // Exactly 2 transactions exist for this key in PostgreSQL
        List<PaymentTransaction> txs = paymentTransactionRepository.findAll().stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .toList();
        assertEquals(2, txs.size(), "PostgreSQL must contain exactly 2 transaction records");
    }

    @Test
    @DisplayName("PostgreSQL Stuck Key Recovery: Stuck IN_PROGRESS key with no payment is marked FAILED")
    void testPostgreSqlStuckKeyWithNoPayment_RecoveredAsFailed() {
        String key = "PG_STUCK_NOPAY_" + System.nanoTime();
        Instant pastTime = Instant.now().minus(Duration.ofMinutes(5));

        IdempotencyKeyRecord record = new IdempotencyKeyRecord(key, "hash_pg_1", pastTime, pastTime.plus(Duration.ofHours(24)));
        record.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(record);

        int recovered = idempotencyService.recoverStuckKeys(Instant.now(), Duration.ofSeconds(120));
        assertEquals(1, recovered, "PostgreSQL must recover 1 stuck key");

        IdempotencyKeyRecord updated = idempotencyKeyRepository.findByKeyValue(key).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.FAILED, updated.getStatus());
        assertEquals(500, updated.getHttpStatusCode());
    }

    @Test
    @DisplayName("PostgreSQL Stuck Key Recovery: Stuck key with successful payment is marked COMPLETED without double-charging")
    void testPostgreSqlStuckKeyWithSuccessfulPayment_RecoveredAsCompletedWithoutDoubleCharge() {
        String userCode = "USR_PG_REC_" + System.nanoTime();
        User user = new User(userCode, "PG User", userCode + "@example.com", 100000L);
        user.setBalance(90000L);
        userRepository.saveAndFlush(user);

        String key = "PG_STUCK_PAID_" + System.nanoTime();
        Instant pastTime = Instant.now().minus(Duration.ofMinutes(5));

        String paymentId = "PAY_PG_REC_" + System.nanoTime();
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, key, userCode, 10000L, "USD", "CREDIT_CARD", "PG Recovered payment"
        );
        com.paymentsystem.test.PaymentTestHelper.walkToState(tx, PaymentStatus.SUCCESS);
        paymentTransactionRepository.saveAndFlush(tx);

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, 10000L, "USD", "CREDIT_CARD", "PG Recovered payment"
        );
        String requestHash = idempotencyService.computePayloadHash(req);

        IdempotencyKeyRecord record = new IdempotencyKeyRecord(key, requestHash, pastTime, pastTime.plus(Duration.ofHours(24)));
        record.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(record);

        int recovered = idempotencyService.recoverStuckKeys(Instant.now(), Duration.ofSeconds(120));
        assertEquals(1, recovered, "PostgreSQL must recover 1 stuck key");

        IdempotencyKeyRecord updated = idempotencyKeyRepository.findByKeyValue(key).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, updated.getStatus());
        assertEquals(200, updated.getHttpStatusCode());

        // Resubmit payment: must return cached response and NOT charge balance again
        PaymentResponse response = paymentProcessingService.initiatePayment(req);
        assertNotNull(response);
        assertEquals(paymentId, response.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());

        User userAfter = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(90000L, userAfter.getBalance(), "PostgreSQL balance must NOT be double-charged");
    }

    @Test
    @DisplayName("PostgreSQL Stuck Key Recovery: Key within threshold is not touched")
    void testPostgreSqlKeyWithinThreshold_NotTouchedByRecovery() {
        String key = "PG_ACTIVE_" + System.nanoTime();
        Instant recentTime = Instant.now().minus(Duration.ofSeconds(20));

        IdempotencyKeyRecord record = new IdempotencyKeyRecord(key, "hash_pg_act", recentTime, recentTime.plus(Duration.ofHours(24)));
        record.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(record);

        int recovered = idempotencyService.recoverStuckKeys(Instant.now(), Duration.ofSeconds(120));
        assertEquals(0, recovered);

        IdempotencyKeyRecord untouched = idempotencyKeyRepository.findByKeyValue(key).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.IN_PROGRESS, untouched.getStatus());
    }
}
