package com.paymentsystem.service;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency & Performance Stress Benchmark for ApexPay Distributed Payment Engine.
 *
 * Simulates extreme duplicate transaction contention:
 * - 1,000 concurrent payment requests submitted simultaneously with identical idempotency key and payload.
 * - Bounded thread pool with configurable worker threads.
 * - Simultaneous release via CountDownLatch barrier.
 * - Rigorous verification of exactly-once payment processing, balance deduction, and database consistency.
 * - High-resolution per-request latency & batch throughput measurement.
 * - Repeatable benchmark execution across 3 isolated test runs.
 *
 * NOTE: Service-Level Performance Benchmark (direct service invocation in-process).
 * Database: In-Memory H2 (PostgreSQL compatibility mode). Results represent in-memory H2 performance
 * and do not represent production multi-node PostgreSQL / network latency characteristics.
 */
@SpringBootTest
@ActiveProfiles("dev")
public class ConcurrentPaymentTest {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrentPaymentTest.class);

    public static final int BENCHMARK_REQUESTS = 1000;
    public static final int DEFAULT_WORKER_COUNT = 50;
    public static final int BENCHMARK_RUNS = 3;

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        // Clean baseline state for each test method
        paymentTransactionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    /**
     * DTO to capture comprehensive benchmark performance & concurrency telemetry.
     */
    public static class BenchmarkResult {
        public int runIndex;
        public int totalRequestsSubmitted;
        public int configuredWorkerCount;
        public int completedRequests;
        public int originalChargesProcessed;
        public int cachedReplays;
        public int expectedDuplicateConflicts;
        public int unexpectedFailures;
        public double totalExecutionTimeMs;
        public double throughputRps;
        public double avgLatencyMs;
        public double p50LatencyMs;
        public double p95LatencyMs;
        public double p99LatencyMs;
        public double maxLatencyMs;

        @Override
        public String toString() {
            return String.format(
                    "Benchmark Run #%d Results (Service-Level Performance):\n" +
                    "  [Concurrency Metrics]\n" +
                    "    - Total Requests Submitted:   %d\n" +
                    "    - Configured Worker Count:    %d\n" +
                    "    - Completed Requests:         %d\n" +
                    "    - Original Charges:           %d\n" +
                    "    - Cached Replays:             %d\n" +
                    "    - Expected Conflicts (409):   %d\n" +
                    "    - Unexpected Failures:        %d\n" +
                    "  [Performance Metrics]\n" +
                    "    - Total Execution Time:       %.2f ms\n" +
                    "    - Service Throughput:         %.2f req/sec\n" +
                    "    - Average Latency:            %.3f ms\n" +
                    "    - Median Latency (p50):       %.3f ms\n" +
                    "    - 95th Percentile (p95):      %.3f ms\n" +
                    "    - 99th Percentile (p99):      %.3f ms\n" +
                    "    - Maximum Latency:            %.3f ms",
                    runIndex, totalRequestsSubmitted, configuredWorkerCount, completedRequests,
                    originalChargesProcessed, cachedReplays, expectedDuplicateConflicts, unexpectedFailures,
                    totalExecutionTimeMs, throughputRps, avgLatencyMs, p50LatencyMs, p95LatencyMs, p99LatencyMs, maxLatencyMs
            );
        }
    }

    @Test
    @DisplayName("Concurrency Benchmark: 1,000 Duplicate Requests with SAME Idempotency Key across 3 Repeatable Runs")
    void test1000ConcurrentDuplicateRequests_RepeatableBenchmark() throws Exception {
        int workerCount = Integer.getInteger("benchmark.workers", DEFAULT_WORKER_COUNT);
        List<BenchmarkResult> results = new ArrayList<>();

        System.out.println("================================================================================");
        System.out.println(" STARTING 1,000-REQUEST CONCURRENCY STRESS BENCHMARK (3 ISOLATED RUNS)");
        System.out.println(" Target: " + BENCHMARK_REQUESTS + " requests per run | Worker Pool: " + workerCount + " threads");
        System.out.println(" Mode: Service-Level In-Process Invocation | DB: H2 (PostgreSQL Mode)");
        System.out.println("================================================================================");

        for (int run = 1; run <= BENCHMARK_RUNS; run++) {
            BenchmarkResult result = executeSingleStressBenchmark(run, BENCHMARK_REQUESTS, workerCount);
            results.add(result);
            System.out.println(result);
            System.out.println("--------------------------------------------------------------------------------");
        }

        // Print final comparison summary table
        printBenchmarkSummaryTable(results);

        // Global invariant assertions across all runs
        for (BenchmarkResult res : results) {
            assertEquals(BENCHMARK_REQUESTS, res.totalRequestsSubmitted, "Submitted request count mismatch");
            assertEquals(BENCHMARK_REQUESTS, res.completedRequests, "All requests must complete");
            assertEquals(1, res.originalChargesProcessed, "Exactly 1 original charge must be processed per run!");
            assertEquals(0, res.unexpectedFailures, "Zero unexpected failures allowed per run!");
            assertEquals(
                    BENCHMARK_REQUESTS,
                    res.originalChargesProcessed + res.cachedReplays + res.expectedDuplicateConflicts,
                    "Sum of original charges, cached replays, and expected duplicate conflicts must equal total requests"
            );
            assertTrue(res.throughputRps > 0, "Throughput must be positive");
        }
    }

    /**
     * Executes a single 1,000-request concurrency stress run with strictly isolated test data.
     */
    private BenchmarkResult executeSingleStressBenchmark(int runIndex, int totalRequests, int workerCount) throws Exception {
        String userCode = "USR_BENCH_" + runIndex + "_" + System.nanoTime();
        String idempotencyKey = "IDEM_BENCH_" + runIndex + "_" + System.nanoTime();
        BigDecimal initialBalance = new BigDecimal("1000.0000");
        BigDecimal paymentAmount = new BigDecimal("100.0000");

        // 1. Prepare isolated test user
        User testUser = new User(userCode, "Benchmark User " + runIndex, userCode.toLowerCase() + "@apexpay.test", initialBalance);
        userRepository.saveAndFlush(testUser);

        // 2. Setup concurrency infrastructure
        ExecutorService executorService = Executors.newFixedThreadPool(workerCount);
        CountDownLatch readyLatch = new CountDownLatch(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);

        AtomicInteger originalCharges = new AtomicInteger(0);
        AtomicInteger cachedReplays = new AtomicInteger(0);
        AtomicInteger duplicateConflicts = new AtomicInteger(0);
        AtomicInteger unexpectedFailures = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Double> latencyRecords = new ConcurrentLinkedQueue<>();

        // 3. Submit 1,000 tasks
        for (int i = 0; i < totalRequests; i++) {
            executorService.submit(() -> {
                // Signal that this worker is ready at the starting gate
                if (readyLatch.getCount() > 0) {
                    readyLatch.countDown();
                }

                try {
                    // Block until the start latch is released
                    startLatch.await();

                    long reqStartNanos = System.nanoTime();
                    try {
                        InitiatePaymentRequest req = new InitiatePaymentRequest(
                                idempotencyKey,
                                userCode,
                                paymentAmount,
                                "USD",
                                "CREDIT_CARD",
                                "1000-request concurrency stress test - Run " + runIndex
                        );

                        PaymentResponse response = paymentProcessingService.initiatePayment(req);
                        long reqEndNanos = System.nanoTime();
                        double latencyMs = (reqEndNanos - reqStartNanos) / 1_000_000.0;
                        latencyRecords.add(latencyMs);

                        if (response != null && response.getStatus() == PaymentStatus.SUCCESS) {
                            if (Boolean.TRUE.equals(response.getIsCachedIdempotentResponse())) {
                                cachedReplays.incrementAndGet();
                            } else {
                                originalCharges.incrementAndGet();
                            }
                        } else {
                            unexpectedFailures.incrementAndGet();
                        }

                    } catch (DuplicateRequestException e) {
                        // Expected in-flight duplicate conflict (HTTP 409 equivalent)
                        long reqEndNanos = System.nanoTime();
                        double latencyMs = (reqEndNanos - reqStartNanos) / 1_000_000.0;
                        latencyRecords.add(latencyMs);
                        duplicateConflicts.incrementAndGet();

                    } catch (Exception e) {
                        long reqEndNanos = System.nanoTime();
                        double latencyMs = (reqEndNanos - reqStartNanos) / 1_000_000.0;
                        latencyRecords.add(latencyMs);
                        unexpectedFailures.incrementAndGet();
                        unexpectedExceptions.add(e);
                        logger.error("Unexpected failure in concurrency worker: {}", e.getMessage(), e);
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    unexpectedFailures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 4. Release starting gate
        boolean ready = readyLatch.await(10, TimeUnit.SECONDS);
        assertTrue(ready, "Worker threads failed to achieve ready state within timeout");

        long batchStartNanos = System.nanoTime();
        startLatch.countDown(); // Unleash all workers simultaneously

        // 5. Await completion with explicit safety timeout
        boolean finished = finishLatch.await(60, TimeUnit.SECONDS);
        long batchEndNanos = System.nanoTime();

        executorService.shutdown();
        boolean terminated = executorService.awaitTermination(5, TimeUnit.SECONDS);
        if (!terminated) {
            executorService.shutdownNow();
        }

        assertTrue(finished, "Stress benchmark timed out awaiting completion of 1000 requests");

        if (!unexpectedExceptions.isEmpty()) {
            fail("Encountered unexpected exceptions during benchmark: " + unexpectedExceptions.get(0).getMessage());
        }

        // 6. Calculate performance metrics
        double totalExecutionTimeMs = (batchEndNanos - batchStartNanos) / 1_000_000.0;
        double throughputRps = (totalRequests / (totalExecutionTimeMs / 1000.0));

        List<Double> sortedLatencies = new ArrayList<>(latencyRecords);
        Collections.sort(sortedLatencies);

        double sumLatency = 0.0;
        for (Double lat : sortedLatencies) {
            sumLatency += lat;
        }
        double avgLatencyMs = sortedLatencies.isEmpty() ? 0.0 : (sumLatency / sortedLatencies.size());
        double p50LatencyMs = calculatePercentile(sortedLatencies, 50.0);
        double p95LatencyMs = calculatePercentile(sortedLatencies, 95.0);
        double p99LatencyMs = calculatePercentile(sortedLatencies, 99.0);
        double maxLatencyMs = sortedLatencies.isEmpty() ? 0.0 : sortedLatencies.get(sortedLatencies.size() - 1);

        // 7. Rigorous Database State Validation
        validateDatabaseCorrectness(userCode, idempotencyKey, initialBalance, paymentAmount);

        // 8. Populate result DTO
        BenchmarkResult res = new BenchmarkResult();
        res.runIndex = runIndex;
        res.totalRequestsSubmitted = totalRequests;
        res.configuredWorkerCount = workerCount;
        res.completedRequests = originalCharges.get() + cachedReplays.get() + duplicateConflicts.get() + unexpectedFailures.get();
        res.originalChargesProcessed = originalCharges.get();
        res.cachedReplays = cachedReplays.get();
        res.expectedDuplicateConflicts = duplicateConflicts.get();
        res.unexpectedFailures = unexpectedFailures.get();
        res.totalExecutionTimeMs = totalExecutionTimeMs;
        res.throughputRps = throughputRps;
        res.avgLatencyMs = avgLatencyMs;
        res.p50LatencyMs = p50LatencyMs;
        res.p95LatencyMs = p95LatencyMs;
        res.p99LatencyMs = p99LatencyMs;
        res.maxLatencyMs = maxLatencyMs;

        return res;
    }

    /**
     * Validates database invariants to ensure zero double-spending, exactly one payment transaction,
     * and a consistent idempotency key record.
     */
    private void validateDatabaseCorrectness(String userCode, String idempotencyKey, BigDecimal initialBalance, BigDecimal paymentAmount) {
        // Query payments table directly from DB
        List<PaymentTransaction> transactions = paymentTransactionRepository.findAll().stream()
                .filter(tx -> idempotencyKey.equals(tx.getIdempotencyKey()))
                .toList();

        // Exactly 1 payment record must exist
        assertEquals(1, transactions.size(), "Database MUST contain EXACTLY ONE transaction for the idempotency key!");

        PaymentTransaction tx = transactions.get(0);
        assertEquals(PaymentStatus.SUCCESS, tx.getStatus(), "Persisted payment status must be SUCCESS");
        assertEquals(userCode, tx.getUserId(), "Persisted payment must belong to the correct test user");
        assertEquals(0, paymentAmount.compareTo(tx.getAmount()), "Persisted payment amount must match requested charge amount");

        // Verify user account balance was deducted exactly once
        User updatedUser = userRepository.findByUserCode(userCode)
                .orElseThrow(() -> new AssertionError("User not found in DB: " + userCode));
        BigDecimal expectedBalance = initialBalance.subtract(paymentAmount);
        assertEquals(
                0,
                expectedBalance.stripTrailingZeros().compareTo(updatedUser.getBalance().stripTrailingZeros()),
                String.format("User balance deduction mismatch! Expected %s but found %s", expectedBalance, updatedUser.getBalance())
        );

        // Verify idempotency record state
        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(idempotencyKey)
                .orElseThrow(() -> new AssertionError("Idempotency record not found in DB: " + idempotencyKey));
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, record.getStatus(), "Idempotency record must be in COMPLETED state");
        assertEquals(200, record.getHttpStatusCode(), "Idempotency record must store HTTP 200");
        assertNotNull(record.getResponseBody(), "Idempotency record response body must not be null");
        assertTrue(record.getResponseBody().contains(tx.getPaymentId()), "Idempotency cached response must reference payment ID");
    }

    /**
     * Calculates the p-th percentile from a sorted list of latencies using the nearest-rank method:
     * rank = ceil((p / 100) * N)
     * index = max(0, rank - 1)
     *
     * @param sortedLatencies sorted list of latency measurements
     * @param percentile percentile value between 0 and 100 (e.g. 50.0, 95.0, 99.0)
     * @return latency value at the specified percentile
     */
    private double calculatePercentile(List<Double> sortedLatencies, double percentile) {
        if (sortedLatencies.isEmpty()) {
            return 0.0;
        }
        int n = sortedLatencies.size();
        int rank = (int) Math.ceil((percentile / 100.0) * n);
        int index = Math.max(0, Math.min(rank - 1, n - 1));
        return sortedLatencies.get(index);
    }

    /**
     * Formats and prints a comparison table of all benchmark runs.
     */
    private void printBenchmarkSummaryTable(List<BenchmarkResult> results) {
        System.out.println("=========================================================================================================");
        System.out.println(" APEXPAY 1,000-REQUEST CONCURRENCY BENCHMARK SUMMARY (SERVICE-LEVEL PERFORMANCE)");
        System.out.println("=========================================================================================================");
        System.out.printf("%-6s | %-8s | %-8s | %-12s | %-10s | %-12s | %-10s | %-10s | %-10s%n",
                "Run", "Requests", "Workers", "Throughput", "Total Time", "Avg Latency", "p50 (Med)", "p95", "p99");
        System.out.println("---------------------------------------------------------------------------------------------------------");

        for (BenchmarkResult r : results) {
            System.out.printf("#%-5d | %-8d | %-8d | %8.1f rps | %8.1f ms | %8.3f ms | %8.3f ms | %8.3f ms | %8.3f ms%n",
                    r.runIndex, r.totalRequestsSubmitted, r.configuredWorkerCount,
                    r.throughputRps, r.totalExecutionTimeMs,
                    r.avgLatencyMs, r.p50LatencyMs, r.p95LatencyMs, r.p99LatencyMs);
        }
        System.out.println("=========================================================================================================");
    }
}
