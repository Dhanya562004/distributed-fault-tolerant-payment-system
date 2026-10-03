package com.paymentsystem.service;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
public class ConcurrentPaymentTest {

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
        userRepository.findByUserCode("USR_CONCURRENCY_1").ifPresent(userRepository::delete);
        userRepository.save(new User("USR_CONCURRENCY_1", "Concurrency Test User", "concurrency@example.com", new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("High Concurrency Test: 10 Parallel Requests with SAME Idempotency Key must execute EXACTLY ONCE")
    void testConcurrentIdenticalRequests_PreventsDoubleSpending() throws InterruptedException {
        int threadCount = 10;
        String idempotencyKey = "IDEM_CONCURRENT_9999";
        BigDecimal paymentAmount = new BigDecimal("100.00");

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateConflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    InitiatePaymentRequest req = new InitiatePaymentRequest(
                            idempotencyKey,
                            "USR_CONCURRENCY_1",
                            paymentAmount,
                            "USD",
                            "CREDIT_CARD",
                            "Concurrent payment test"
                    );

                    PaymentResponse response = paymentProcessingService.initiatePayment(req);
                    if (response != null && response.getStatus() == PaymentStatus.SUCCESS) {
                        successCount.incrementAndGet();
                    }
                } catch (DuplicateRequestException e) {
                    duplicateConflictCount.incrementAndGet();
                } catch (Exception e) {
                    duplicateConflictCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean finished = finishLatch.await(15, TimeUnit.SECONDS);
        executorService.shutdown();

        assertTrue(finished, "All threads should complete execution within timeout");

        User user = userRepository.findByUserCode("USR_CONCURRENCY_1").orElseThrow();
        assertEquals(new BigDecimal("900.0000").stripTrailingZeros(), user.getBalance().stripTrailingZeros(), "User balance MUST be deducted exactly once!");

        long txCount = paymentTransactionRepository.findAll().stream()
                .filter(tx -> idempotencyKey.equals(tx.getIdempotencyKey()))
                .count();
        assertEquals(1, txCount, "DB MUST contain exactly 1 transaction for the idempotency key!");

        System.out.println("CONCURRENCY TEST PASSED: Successes=" + successCount.get() + ", Lock Conflicts Handled=" + duplicateConflictCount.get());
    }
}
