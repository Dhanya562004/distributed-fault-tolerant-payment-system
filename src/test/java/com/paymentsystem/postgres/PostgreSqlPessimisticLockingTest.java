package com.paymentsystem.postgres;

import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL 16 Pessimistic Locking Integration Verification.
 *
 * Verifies the actual behavior of @Lock(LockModeType.PESSIMISTIC_WRITE) / SELECT FOR UPDATE:
 * 1. Transaction A acquires an exclusive row-level lock using findByUserCodeForUpdate().
 * 2. Transaction B concurrently attempts findByUserCodeForUpdate() and is blocked by PostgreSQL's lock manager.
 * 3. Proves Transaction B cannot acquire the lock or read the row until Transaction A commits.
 * 4. Once Transaction A commits and releases the row lock, Transaction B acquires the lock,
 *    observes the committed change, and applies its update sequentially.
 * 5. Verifies strict transaction consistency and serial execution without lost updates.
 */
public class PostgreSqlPessimisticLockingTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
    }

    @Test
    @DisplayName("PostgreSQL Pessimistic Locking: findByUserCodeForUpdate blocks competing transaction until commit")
    void testUserPessimisticLocking_BlocksCompetingTransaction() throws Exception {
        String userCode = "USR_PESS_" + System.currentTimeMillis();
        BigDecimal initialBalance = new BigDecimal("1000.0000");

        userRepository.saveAndFlush(new User(userCode, "Pessimistic Test User", userCode + "@pg.test", initialBalance));

        CountDownLatch txALockAcquired = new CountDownLatch(1);
        CountDownLatch txAHoldLatch = new CountDownLatch(1);
        CountDownLatch txBStarted = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(2);

        AtomicBoolean txBCompleted = new AtomicBoolean(false);
        AtomicReference<BigDecimal> txBBalanceRead = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Transaction A: Acquires row lock with SELECT FOR UPDATE, then pauses
        executor.submit(() -> {
            TransactionTemplate txA = new TransactionTemplate(transactionManager);
            txA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                txA.execute(status -> {
                    // Executes: SELECT ... FROM users WHERE user_code = ? FOR UPDATE
                    User userA = userRepository.findByUserCodeForUpdate(userCode).orElseThrow();
                    txALockAcquired.countDown();

                    try {
                        // Hold lock until told to release
                        txAHoldLatch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    userA.setBalance(new BigDecimal("900.0000"));
                    userRepository.saveAndFlush(userA);
                    return null;
                });
            } catch (Exception e) {
                errorRef.set(e);
            } finally {
                allDone.countDown();
            }
        });

        // Transaction B: Waits for Tx A to acquire lock, then attempts to acquire the SAME lock
        executor.submit(() -> {
            TransactionTemplate txB = new TransactionTemplate(transactionManager);
            txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                // Ensure Tx A has already locked the row in PostgreSQL
                txALockAcquired.await(5, TimeUnit.SECONDS);
                txBStarted.countDown();

                txB.execute(status -> {
                    // This call will BLOCK in PostgreSQL until Tx A commits!
                    User userB = userRepository.findByUserCodeForUpdate(userCode).orElseThrow();
                    txBBalanceRead.set(userB.getBalance());
                    userB.setBalance(userB.getBalance().subtract(new BigDecimal("100.0000")));
                    userRepository.saveAndFlush(userB);
                    txBCompleted.set(true);
                    return null;
                });
            } catch (Exception e) {
                errorRef.set(e);
            } finally {
                allDone.countDown();
            }
        });

        // Wait until Tx A has acquired lock and Tx B has initiated its query
        assertTrue(txALockAcquired.await(5, TimeUnit.SECONDS), "Tx A must acquire lock");
        assertTrue(txBStarted.await(5, TimeUnit.SECONDS), "Tx B must start execution");

        // Give Tx B 400ms to attempt query: Tx B MUST be blocked waiting on PostgreSQL row lock
        Thread.sleep(400);
        assertFalse(txBCompleted.get(), "CRITICAL: Transaction B must be blocked waiting on row lock held by Transaction A!");

        // Now release Tx A to commit
        txAHoldLatch.countDown();

        // Await completion of both transactions
        assertTrue(allDone.await(10, TimeUnit.SECONDS), "Both transactions must finish within safety timeout");
        executor.shutdown();

        assertNull(errorRef.get(), "No unexpected error during pessimistic lock execution");
        assertTrue(txBCompleted.get(), "Transaction B must complete after Tx A commits and releases lock");

        // Verify Tx B observed Tx A's committed balance ($900.00), not the initial $1,000.00
        assertEquals(0, new BigDecimal("900.0000").compareTo(txBBalanceRead.get()),
                "Tx B must read Tx A's committed balance ($900.00) after unblocking");

        // Verify final balance after both serialized deductions: 1000 - 100 - 100 = 800.00
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("800.0000").compareTo(finalUser.getBalance()),
                "Final balance must reflect both serialized updates ($800.00)");
    }

    @Test
    @DisplayName("PostgreSQL Pessimistic Locking: findByPaymentIdForUpdate serializes payment record mutation")
    void testPaymentTransactionPessimisticLocking() throws Exception {
        String paymentId = "PAY_PESS_" + UUID.randomUUID().toString().substring(0, 8);
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, "IDEM_PESS_TX", "USR_DUMMY", new BigDecimal("75.0000"), "USD", "CREDIT_CARD", "Pessimistic Tx Test"
        );
        paymentTransactionRepository.saveAndFlush(tx);

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);
        AtomicBoolean txBFinished = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Tx A: locks payment record
        executor.submit(() -> {
            TransactionTemplate tplA = new TransactionTemplate(transactionManager);
            tplA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tplA.execute(status -> {
                PaymentTransaction p = paymentTransactionRepository.findByPaymentIdForUpdate(paymentId).orElseThrow();
                lockAcquired.countDown();
                try {
                    holdLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                p.setStatus(PaymentStatus.SUCCESS);
                paymentTransactionRepository.saveAndFlush(p);
                return null;
            });
            finishLatch.countDown();
        });

        // Tx B: attempts to lock payment record concurrently
        executor.submit(() -> {
            try {
                lockAcquired.await(5, TimeUnit.SECONDS);
                TransactionTemplate tplB = new TransactionTemplate(transactionManager);
                tplB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tplB.execute(status -> {
                    PaymentTransaction p = paymentTransactionRepository.findByPaymentIdForUpdate(paymentId).orElseThrow();
                    p.setDescription("Updated by Tx B after lock release");
                    paymentTransactionRepository.saveAndFlush(p);
                    txBFinished.set(true);
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                finishLatch.countDown();
            }
        });

        lockAcquired.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        assertFalse(txBFinished.get(), "Tx B must be blocked waiting for PaymentTransaction row lock");

        holdLatch.countDown();
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        PaymentTransaction finalTx = paymentTransactionRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, finalTx.getStatus());
        assertEquals("Updated by Tx B after lock release", finalTx.getDescription());
    }
}
