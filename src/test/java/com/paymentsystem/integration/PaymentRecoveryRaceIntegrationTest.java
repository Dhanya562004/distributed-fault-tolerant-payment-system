package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.idempotency.IdempotencyKeyRecord;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InvalidTransactionStateException;
import com.paymentsystem.idempotency.IdempotencyKeyRepository;
import com.paymentsystem.outbox.OutboxEventRepository;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.idempotency.IdempotencyService;
import com.paymentsystem.payment.PaymentTransactionCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Race condition test: Slow worker vs Idempotency Recovery Job.
 *
 * Verifies that if a background worker is stalled (e.g. downstream gateway latency)
 * and the recovery job fires to mark the abandoned transaction as FAILED,
 * when the slow worker finally attempts to commit, it is safely rejected via status check / lock,
 * and user balance is never deducted.
 */
@SpringBootTest
class PaymentRecoveryRaceIntegrationTest {

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentTransactionCoordinator paymentCoordinator;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private String userCode;
    private final long INITIAL_BALANCE = 100000L;

    @BeforeEach
    void setupUser() {
        userCode = "USR_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = new User(userCode, "Race User", userCode + "@paymentsystem.io", INITIAL_BALANCE);
        userRepository.saveAndFlush(user);
    }

    @Test
    @DisplayName("Slow worker vs Recovery Job: Recovery marks FAILED, slow worker fails on commit attempt, balance untouched")
    void testSlowWorkerVsRecoveryJob_WorkerRejected_BalanceUntouched() throws Exception {
        String paymentId = "PAY_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String idemKey = "IDEM_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long chargeAmount = 25000L;

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idemKey, userCode, chargeAmount, "USD", "CREDIT_CARD", "Race condition test"
        );
        String payloadHash = idempotencyService.computePayloadHash(req);

        // 1. Seed payment stuck in PROCESSING older than threshold
        Instant pastTime = Instant.now().minus(Duration.ofMinutes(10));
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, idemKey, userCode, chargeAmount, "USD", "CREDIT_CARD", "Race condition test"
        );
        com.paymentsystem.test.PaymentTestHelper.walkToState(tx, PaymentStatus.PROCESSING);
        tx.setCreatedAt(pastTime);
        tx.setUpdatedAt(pastTime);
        paymentRepository.saveAndFlush(tx);

        IdempotencyKeyRecord keyRecord = new IdempotencyKeyRecord(idemKey, payloadHash, pastTime, pastTime.plus(Duration.ofHours(24)));
        keyRecord.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(keyRecord);

        // 2. Recovery job fires and marks stuck payment FAILED
        int recovered = idempotencyService.recoverStuckKeys(Instant.now(), Duration.ofMinutes(2));
        assertTrue(recovered > 0, "Recovery job must recover the stuck key");

        // Clear test persistence context to ensure reloading from DB
        entityManager.clear();

        // Verify state after recovery
        PaymentTransaction recoveredTx = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.FAILED, recoveredTx.getStatus(), "Payment must be marked FAILED by recovery job");

        IdempotencyKeyRecord recoveredKey = idempotencyKeyRepository.findByKeyValue(idemKey).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.FAILED, recoveredKey.getStatus());

        // 3. Now slow worker wakes up and attempts to commit SUCCESS via executePaymentAttempt
        InvalidTransactionStateException thrown = assertThrows(InvalidTransactionStateException.class, () -> {
            paymentCoordinator.executePaymentAttempt(paymentId, req);
        }, "Worker must be rejected when attempting to commit against FAILED payment");

        assertTrue(thrown.getMessage().contains("has already FAILED"));

        // 4. Verify balance is NOT deducted
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(INITIAL_BALANCE, finalUser.getBalance(), "User balance must remain untouched");

        // 5. Verify payment remains FAILED
        PaymentTransaction finalTx = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.FAILED, finalTx.getStatus());

        // 6. Verify zero payment.succeeded outbox events
        assertTrue(outboxEventRepository.findByAggregateId(paymentId).isEmpty(), "No outbox event should be persisted");
    }
}
