package com.paymentsystem.postgres;

import com.paymentsystem.dto.DeliveryResult;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.OutboxStatus;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.PaymentTransactionCoordinator;
import com.paymentsystem.worker.OutboxDeliveryWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL 16 Testcontainers Suite for Step 4:
 * Validates Transactional Outbox atomicity, rollback guarantees, concurrent claiming,
 * unique constraints, lease recovery, and state transitions against real PostgreSQL 16.
 */
public class PostgreSqlTransactionalOutboxIntegrationTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionCoordinator paymentCoordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxDeliveryWorker outboxWorker;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();

        // Seed users to satisfy foreign key constraints fk_payments_user in PostgreSQL
        userRepository.findByUserCode("USR_ALICE").orElseGet(() ->
                userRepository.saveAndFlush(new User("USR_ALICE", "Alice", "alice@pg.test", new BigDecimal("1000.0000"))));
        userRepository.findByUserCode("USR_BOB").orElseGet(() ->
                userRepository.saveAndFlush(new User("USR_BOB", "Bob", "bob@pg.test", new BigDecimal("1000.0000"))));
    }

    @Test
    @DisplayName("1. PostgreSQL Atomicity: Payment state and outbox event commit together atomically")
    void testPaymentAndOutbox_AtomicityOnPostgreSql() {
        String paymentId = "PAY_PG_ATOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PaymentTransaction tx = new PaymentTransaction(paymentId, "IDEM_" + paymentId, "USR_ALICE",
                new BigDecimal("250.00"), "USD", "CREDIT_CARD", "PG Atomicity Test");

        paymentCoordinator.commitPaymentSuccess(tx);

        // Verify payment is SUCCESS in PostgreSQL
        PaymentTransaction savedPayment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, savedPayment.getStatus());

        // Verify outbox event exists in PostgreSQL
        List<OutboxEvent> outboxList = outboxEventRepository.findByAggregateId(paymentId);
        assertEquals(1, outboxList.size());
        assertEquals("payment.succeeded", outboxList.get(0).getEventType());
        assertEquals(OutboxStatus.PENDING, outboxList.get(0).getStatus());
    }

    @Test
    @DisplayName("2. PostgreSQL Rollback: Aborted transaction rolls back both payment and outbox event")
    void testRollbackBehavior_RollsBackBothOnPostgreSql() {
        String paymentId = "PAY_PG_RB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        assertThrows(RuntimeException.class, () -> {
            txTemplate.executeWithoutResult(status -> {
                PaymentTransaction tx = new PaymentTransaction(paymentId, "IDEM_" + paymentId, "USR_BOB",
                        new BigDecimal("500.00"), "USD", "CREDIT_CARD", "Rollback Test");
                tx.setStatus(PaymentStatus.SUCCESS);
                paymentRepository.saveAndFlush(tx);

                OutboxEvent event = new OutboxEvent("evt_rb_" + paymentId, "payment.succeeded", paymentId, "{}", 5, Instant.now());
                outboxEventRepository.save(event);

                // Simulate unexpected failure triggering database rollback
                throw new RuntimeException("Simulated business failure forcing database rollback");
            });
        });

        // Verify PostgreSQL rolled back both tables
        assertTrue(paymentRepository.findByPaymentId(paymentId).isEmpty(), "Payment must not exist after rollback");
        assertTrue(outboxEventRepository.findByAggregateId(paymentId).isEmpty(), "Outbox event must not exist after rollback");
    }

    @Test
    @DisplayName("3. PostgreSQL Concurrent Claiming: Exactly one worker claims event under high concurrency")
    void testConcurrentOutboxClaiming_MutualExclusion() throws Exception {
        OutboxEvent event = new OutboxEvent("evt_claim_race", "payment.succeeded", "PAY_RACE_1", "{}", 5, Instant.now());
        event = outboxEventRepository.saveAndFlush(event);
        final Long eventId = event.getId();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successClaims = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String workerId = "worker-thread-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Instant now = Instant.now();
                    Instant leaseUntil = now.plusSeconds(60);
                    Integer updated = txTemplate.execute(status ->
                            outboxEventRepository.claimEvent(eventId, workerId, leaseUntil, now, OutboxStatus.PROCESSING));
                    if (updated != null && updated == 1) {
                        successClaims.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successClaims.get(), "Exactly one worker thread must succeed in claiming the outbox event in PostgreSQL");
    }

    @Test
    @DisplayName("4. PostgreSQL Unique Event ID Constraint Enforcement")
    void testUniqueEventIdEnforcement() {
        String duplicateEventId = "evt_unique_constraint_test";
        OutboxEvent event1 = new OutboxEvent(duplicateEventId, "payment.succeeded", "PAY_UQ_1", "{}", 5, Instant.now());
        outboxEventRepository.saveAndFlush(event1);

        OutboxEvent event2 = new OutboxEvent(duplicateEventId, "payment.failed", "PAY_UQ_2", "{}", 5, Instant.now());
        assertThrows(DataIntegrityViolationException.class, () -> {
            outboxEventRepository.saveAndFlush(event2);
        }, "PostgreSQL unique index idx_outbox_event_id must reject duplicate event IDs");
    }

    @Test
    @DisplayName("5. PostgreSQL Delivery State Transitions: PENDING -> PROCESSING -> DELIVERED")
    void testDeliveryStateTransitions() {
        OutboxEvent event = new OutboxEvent("evt_trans_1", "payment.succeeded", "PAY_TRANS_1", "{}", 5, Instant.now());
        event = outboxEventRepository.saveAndFlush(event);
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        final Long eventId = event.getId();

        // Worker claims -> PROCESSING inside short transaction
        Instant now = Instant.now();
        Integer claimed = txTemplate.execute(status ->
                outboxEventRepository.claimEvent(eventId, "worker-trans", now.plusSeconds(60), now, OutboxStatus.PROCESSING));
        assertNotNull(claimed);
        assertEquals(1, claimed);

        OutboxEvent processingEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertEquals(OutboxStatus.PROCESSING, processingEvent.getStatus());
        assertEquals("worker-trans", processingEvent.getLeaseOwner());

        // Delivery successful -> DELIVERED
        outboxWorker.recordDeliveryResult(eventId, DeliveryResult.success(200, "{\"ok\":true}"));

        OutboxEvent deliveredEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertEquals(OutboxStatus.DELIVERED, deliveredEvent.getStatus());
        assertNotNull(deliveredEvent.getDeliveredAt());
        assertNull(deliveredEvent.getLeaseOwner());
        assertNull(deliveredEvent.getLockedUntil());
    }

    @Test
    @DisplayName("6. PostgreSQL Dead-Letter Persistence on retry exhaustion")
    void testDeadLetterPersistence() {
        OutboxEvent event = new OutboxEvent("evt_dlq_pg", "payment.succeeded", "PAY_DLQ_1", "{}", 3, Instant.now());
        event.setAttemptCount(2); // next retry will be 3rd (max)
        event = outboxEventRepository.saveAndFlush(event);

        outboxWorker.recordDeliveryResult(event.getId(), DeliveryResult.retryableFailure(503, "Service Unavailable"));

        OutboxEvent deadEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxStatus.DEAD_LETTER, deadEvent.getStatus());
        assertEquals(3, deadEvent.getAttemptCount());
        assertTrue(deadEvent.getLastError().contains("503"));
    }

    @Test
    @DisplayName("7. PostgreSQL Worker Lease Recovery after worker crash")
    void testWorkerLeaseRecovery_AfterCrash() {
        // Event was claimed by crashed worker, lockedUntil was 10 seconds ago
        OutboxEvent event = new OutboxEvent("evt_lease_crash", "payment.succeeded", "PAY_CRASH_1", "{}", 5, Instant.now().minusSeconds(100));
        event.setStatus(OutboxStatus.PROCESSING);
        event.setLeaseOwner("crashed-worker-999");
        event.setLockedUntil(Instant.now().minusSeconds(10));
        event = outboxEventRepository.saveAndFlush(event);
        final Long eventId = event.getId();

        // Active worker finds candidate events: expired lease is included!
        List<Long> candidates = outboxEventRepository.findCandidateEventIds(Instant.now(), PageRequest.of(0, 10));
        assertTrue(candidates.contains(eventId), "Expired worker lease must be surfaced as a claim candidate in PostgreSQL");

        // New worker claims event inside short transaction
        Integer claimed = txTemplate.execute(status ->
                outboxEventRepository.claimEvent(eventId, "recovery-worker", Instant.now().plusSeconds(60), Instant.now(), OutboxStatus.PROCESSING));
        assertNotNull(claimed);
        assertEquals(1, claimed);

        OutboxEvent recovered = outboxEventRepository.findById(eventId).orElseThrow();
        assertEquals("recovery-worker", recovered.getLeaseOwner());
    }

    @Test
    @DisplayName("8. PostgreSQL Concurrent Delivery: Multiple workers process non-overlapping events")
    void testConcurrentDistinctEventClaiming() throws Exception {
        int eventCount = 10;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            OutboxEvent e = new OutboxEvent("evt_distinct_" + i, "payment.succeeded", "PAY_D_" + i, "{}", 5, Instant.now());
            ids.add(outboxEventRepository.saveAndFlush(e).getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Set<Long> processedIds = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch latch = new CountDownLatch(ids.size());

        for (Long id : ids) {
            pool.submit(() -> {
                try {
                    String workerId = "worker-" + Thread.currentThread().getName();
                    Instant now = Instant.now();
                    Integer claimed = txTemplate.execute(status ->
                            outboxEventRepository.claimEvent(id, workerId, now.plusSeconds(30), now, OutboxStatus.PROCESSING));
                    if (claimed != null && claimed == 1) {
                        processedIds.add(id);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(eventCount, processedIds.size(), "All 10 distinct events must be claimed across concurrent workers without conflicts");
    }

    @Test
    @DisplayName("9. PostgreSQL Payment Idempotency under concurrent duplicate requests")
    void testPaymentIdempotency_UnderConcurrency() throws Exception {
        String key = "IDEM_CONCUR_PG_" + System.currentTimeMillis();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    txTemplate.executeWithoutResult(status -> {
                        IdempotencyKeyRecord record = new IdempotencyKeyRecord(key, "req_hash_" + key, Instant.now(), Instant.now().plusSeconds(3600));
                        idempotencyKeyRepository.saveAndFlush(record);
                        successCount.incrementAndGet();
                    });
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent thread can acquire the idempotency key in PostgreSQL");
    }

    @Test
    @DisplayName("10. PostgreSQL Compatibility with Step 3 24h expiry logic alongside outbox")
    void testIdempotencyExpiry_AlongsideOutbox() {
        String key = "IDEM_EXPIRED_PG_" + System.currentTimeMillis();
        Instant past = Instant.now().minusSeconds(86400 * 2); // 48 hours in past

        IdempotencyKeyRecord expiredRecord = new IdempotencyKeyRecord(key, "hash1", past, past.plusSeconds(3600));
        expiredRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        idempotencyKeyRepository.saveAndFlush(expiredRecord);

        // Delete expired keys before now inside transaction
        Integer deleted = txTemplate.execute(status ->
                idempotencyKeyRepository.deleteExpiredKeysExcludingStatus(Instant.now(), IdempotencyKeyRecord.Status.IN_PROGRESS));
        assertNotNull(deleted);
        assertTrue(deleted >= 1, "Expired idempotency keys must be cleanly deleted in PostgreSQL");
    }
}
