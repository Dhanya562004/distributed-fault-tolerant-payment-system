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
import org.springframework.dao.OptimisticLockingFailureException;
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
 * Real PostgreSQL 16 Optimistic Locking Integration Verification.
 *
 * Exercises the @Version annotations on User (version) and PaymentTransaction (lock_version)
 * against genuine PostgreSQL transactions to prove lost update prevention:
 * 1. Independent transaction reads stale version.
 * 2. Competing transaction commits update and increments version in PostgreSQL.
 * 3. Stale transaction attempts commit and is rejected with OptimisticLockingFailureException.
 * 4. Persisted record remains strictly consistent in PostgreSQL.
 */
public class PostgreSqlOptimisticLockingTest extends AbstractPostgreSqlIntegrationTest {

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
    @DisplayName("PostgreSQL Optimistic Locking: Competing transaction on User @Version throws OptimisticLockingFailureException")
    void testUserOptimisticLocking_ThrowsOptimisticLockException() {
        String userCode = "USR_OPT_" + System.currentTimeMillis();
        BigDecimal initialBalance = new BigDecimal("1000.0000");

        // 1. Setup user in PostgreSQL with version 0
        User initialUser = new User(userCode, "Optimistic Test User", userCode + "@pg.test", initialBalance);
        User savedUser = userRepository.saveAndFlush(initialUser);
        Long initialVersion = savedUser.getVersion();
        assertNotNull(initialVersion, "Initial version must not be null");

        // 2. Transaction A reads and successfully updates user to 900.00
        TransactionTemplate txA = new TransactionTemplate(transactionManager);
        txA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txA.execute(status -> {
            User userA = userRepository.findByUserCode(userCode).orElseThrow();
            assertEquals(initialVersion, userA.getVersion());
            userA.setBalance(new BigDecimal("900.0000"));
            userRepository.saveAndFlush(userA);
            return null;
        });

        // Verify version incremented in PostgreSQL to initialVersion + 1
        User committedUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(initialVersion + 1, committedUser.getVersion(), "PostgreSQL version must increment after Tx A commits");
        assertEquals(0, new BigDecimal("900.0000").compareTo(committedUser.getBalance()));

        // 3. Transaction B attempts to save the original stale entity (holding version 0)
        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        savedUser.setBalance(new BigDecimal("800.0000")); // Stale update attempt

        assertThrows(OptimisticLockingFailureException.class, () -> {
            txB.execute(status -> {
                userRepository.saveAndFlush(savedUser);
                return null;
            });
        }, "Saving stale entity with outdated @Version in PostgreSQL must trigger OptimisticLockingFailureException");

        // 4. Verify PostgreSQL data integrity remained intact
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(initialVersion + 1, finalUser.getVersion(), "Version should remain at Tx A's committed version");
        assertEquals(0, new BigDecimal("900.0000").compareTo(finalUser.getBalance()), "Balance must remain at Tx A's committed value");
    }

    @Test
    @DisplayName("PostgreSQL Optimistic Locking: Concurrent threads reading same version prevents second update")
    void testConcurrentThreadsOptimisticLocking_PreventsLostUpdate() throws Exception {
        String userCode = "USR_OPT_CONC_" + System.currentTimeMillis();
        User initialUser = new User(userCode, "Concurrent Opt User", userCode + "@pg.test", new BigDecimal("1000.0000"));
        userRepository.saveAndFlush(initialUser);

        CountDownLatch threadReadyLatch = new CountDownLatch(2);
        CountDownLatch startCommitLatch = new CountDownLatch(1);
        CountDownLatch txACommittedLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        AtomicBoolean txASucceeded = new AtomicBoolean(false);
        AtomicBoolean txBCaughtOptimisticException = new AtomicBoolean(false);
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Reads user, waits for startCommitLatch, commits first, then signals txACommittedLatch
        executor.submit(() -> {
            TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
            tx1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx1.execute(status -> {
                    User u1 = userRepository.findByUserCode(userCode).orElseThrow();
                    threadReadyLatch.countDown();
                    try {
                        startCommitLatch.await(5, TimeUnit.SECONDS);
                        u1.setBalance(new BigDecimal("950.0000"));
                        userRepository.saveAndFlush(u1);
                        txASucceeded.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Exception e) {
                unexpectedError.set(e);
            } finally {
                txACommittedLatch.countDown(); // Signals Tx 1 has committed to PostgreSQL
                finishLatch.countDown();
            }
        });

        // Thread 2: Reads user with same version, waits until Tx 1 is 100% committed, then attempts update
        executor.submit(() -> {
            TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
            tx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                tx2.execute(status -> {
                    User u2 = userRepository.findByUserCode(userCode).orElseThrow();
                    threadReadyLatch.countDown();
                    try {
                        startCommitLatch.await(5, TimeUnit.SECONDS);
                        // Deterministically await Tx 1 commit before attempting update
                        boolean tx1Committed = txACommittedLatch.await(5, TimeUnit.SECONDS);
                        assertTrue(tx1Committed, "Tx 1 must commit before Tx 2 attempts commit");
                        u2.setBalance(new BigDecimal("900.0000"));
                        userRepository.saveAndFlush(u2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (OptimisticLockingFailureException e) {
                txBCaughtOptimisticException.set(true);
            } catch (Exception e) {
                if (e.getCause() instanceof org.hibernate.StaleObjectStateException) {
                    txBCaughtOptimisticException.set(true);
                } else {
                    unexpectedError.set(e);
                }
            } finally {
                finishLatch.countDown();
            }
        });

        assertTrue(threadReadyLatch.await(5, TimeUnit.SECONDS), "Both threads must read user before commit");
        startCommitLatch.countDown();
        assertTrue(finishLatch.await(10, TimeUnit.SECONDS), "Both transactions must finish within timeout");
        executor.shutdown();

        assertNull(unexpectedError.get(), "No unexpected exception should occur");
        assertTrue(txASucceeded.get(), "Transaction A should commit successfully");
        assertTrue(txBCaughtOptimisticException.get(), "Transaction B must be rejected due to optimistic lock conflict in PostgreSQL");

        // Verify PostgreSQL row matches Tx A's update
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("950.0000").compareTo(finalUser.getBalance()));
    }

    @Test
    @DisplayName("PostgreSQL Optimistic Locking: PaymentTransaction lock_version enforces consistency")
    void testPaymentTransactionOptimisticLocking() {
        String paymentId = "PAY_OPT_" + UUID.randomUUID().toString().substring(0, 8);
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, "IDEM_OPT_TX", "USR_DUMMY", new BigDecimal("50.0000"), "USD", "CREDIT_CARD", "Opt Tx Test"
        );
        PaymentTransaction savedTx = paymentTransactionRepository.saveAndFlush(tx);
        Long initialLockVersion = savedTx.getLockVersion();

        // Tx A updates status to SUCCESS
        TransactionTemplate txA = new TransactionTemplate(transactionManager);
        txA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txA.execute(status -> {
            PaymentTransaction p = paymentTransactionRepository.findByPaymentId(paymentId).orElseThrow();
            p.setStatus(PaymentStatus.SUCCESS);
            paymentTransactionRepository.saveAndFlush(p);
            return null;
        });

        PaymentTransaction committedTx = paymentTransactionRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(initialLockVersion + 1, committedTx.getLockVersion());
        assertEquals(PaymentStatus.SUCCESS, committedTx.getStatus());

        // Tx B attempts update with stale reference
        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        savedTx.setStatus(PaymentStatus.FAILED); // Stale reference
        assertThrows(OptimisticLockingFailureException.class, () -> {
            txB.execute(status -> {
                paymentTransactionRepository.saveAndFlush(savedTx);
                return null;
            });
        }, "Stale update on PaymentTransaction must trigger OptimisticLockingFailureException in PostgreSQL");
    }
}
