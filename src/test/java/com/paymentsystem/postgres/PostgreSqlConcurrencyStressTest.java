package com.paymentsystem.postgres;

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
import com.paymentsystem.service.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL 16 1,000-Request Concurrency & Performance Stress Benchmark.
 *
 * Executes the Step 1 concurrency scenario against a real PostgreSQL container:
 * - 1,000 duplicate payment requests per run using identical idempotency key and payload.
 * - Bounded thread pool with 50 worker threads.
 * - Coordinated simultaneous microsecond release via CountDownLatch barrier.
 * - 3 isolated repeatable benchmark runs.
 * - High-resolution per-request latency tracking (p50, p95, p99, max) and batch throughput.
 * - Rigorous database-level verification of single charge, zero double-spending, and transaction integrity.
 *
 * NOTE: CI-Runner, PostgreSQL-Backed, Service-Level Performance Benchmark.
 */
public class PostgreSqlConcurrencyStressTest extends AbstractPostgreSqlIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PostgreSqlConcurrencyStressTest.class);

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
        paymentTransactionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

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
                    "PostgreSQL Benchmark Run #%d Results (CI-Runner Service-Level):\n" +
                    "  [Concurrency Metrics]\n" +
                    "    - Total Requests Submitted:   %d\n" +
                    "    - Configured Worker Count:    %d\n" +
                    "    - Completed Requests:         %d\n" +
                    "    - Original Charges (DB):      %d\n" +
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
    @DisplayName("PostgreSQL 16 Concurrency Benchmark: 1,000 Duplicate Requests across 3 Isolated Runs")
    void test1000ConcurrentDuplicateRequests_PostgreSqlBenchmark() throws Exception {
        int workerCount = Integer.getInteger("benchmark.workers", DEFAULT_WORKER_COUNT);
        int runs = Integer.getInteger("benchmark.runs", BENCHMARK_RUNS);
        List<BenchmarkResult> results = new ArrayList<>();

        System.out.println("=========================================================================================================");
        System.out.println(" STARTING POSTGRESQL 16 1,000-REQUEST CONCURRENCY BENCHMARK (" + runs + " ISOLATED RUNS)");
        System.out.println(" Target: " + BENCHMARK_REQUESTS + " requests/run | Workers: " + workerCount + " threads | DB: PostgreSQL 16 (Testcontainers)");
        System.out.println("=========================================================================================================");

        for (int run = 1; run <= runs; run++) {
            BenchmarkResult result = executeSinglePostgreSqlBenchmark(run, BENCHMARK_REQUESTS, workerCount);
            results.add(result);
            System.out.println(result);
            System.out.println("---------------------------------------------------------------------------------------------------------");
        }

        printBenchmarkSummaryTable(results);

        // Assert global invariants across all runs
        for (BenchmarkResult res : results) {
            assertEquals(BENCHMARK_REQUESTS, res.totalRequestsSubmitted);
            assertEquals(BENCHMARK_REQUESTS, res.completedRequests);
            assertEquals(1, res.originalChargesProcessed, "Exactly 1 original charge must be committed in PostgreSQL");
            assertEquals(0, res.unexpectedFailures, "Zero unexpected failures allowed in PostgreSQL benchmark");
            assertEquals(
                    BENCHMARK_REQUESTS,
                    res.originalChargesProcessed + res.cachedReplays + res.expectedDuplicateConflicts,
                    "All 1,000 requests must be accounted for"
            );
            assertTrue(res.throughputRps > 0);
        }
    }

    private BenchmarkResult executeSinglePostgreSqlBenchmark(int runIndex, int totalRequests, int workerCount) throws Exception {
        String userCode = "USR_PG_BENCH_" + runIndex + "_" + System.nanoTime();
        String idempotencyKey = "IDEM_PG_BENCH_" + runIndex + "_" + System.nanoTime();
        BigDecimal initialBalance = new BigDecimal("1000.0000");
        BigDecimal paymentAmount = new BigDecimal("100.0000");

        // 1. Prepare isolated user in PostgreSQL
        User testUser = new User(userCode, "PG Benchmark User " + runIndex, userCode + "@pg.bench", initialBalance);
        userRepository.saveAndFlush(testUser);

        // 2. Setup concurrency coordination
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

        // 3. Submit 1,000 duplicate requests
        for (int i = 0; i < totalRequests; i++) {
            executorService.submit(() -> {
                if (readyLatch.getCount() > 0) {
                    readyLatch.countDown();
                }

                try {
                    startLatch.await();

                    long reqStartNanos = System.nanoTime();
                    try {
                        InitiatePaymentRequest req = new InitiatePaymentRequest(
                                idempotencyKey,
                                userCode,
                                paymentAmount,
                                "USD",
                                "CREDIT_CARD",
                                "PostgreSQL 1000-request stress test - Run " + runIndex
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
                        logger.error("Unexpected error in PostgreSQL worker: {}", e.getMessage(), e);
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
        startLatch.countDown();

        // 5. Await completion with explicit timeout
        boolean finished = finishLatch.await(60, TimeUnit.SECONDS);
        long batchEndNanos = System.nanoTime();

        executorService.shutdown();
        boolean terminated = executorService.awaitTermination(5, TimeUnit.SECONDS);
        if (!terminated) {
            executorService.shutdownNow();
        }

        assertTrue(finished, "PostgreSQL stress benchmark timed out awaiting completion of 1000 requests");

        if (!unexpectedExceptions.isEmpty()) {
            fail("Encountered unexpected exceptions during PostgreSQL benchmark: " + unexpectedExceptions.get(0).getMessage());
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

        // 7. Rigorous PostgreSQL Database State Validation
        validatePostgreSqlDatabaseCorrectness(userCode, idempotencyKey, initialBalance, paymentAmount);

        // 8. Construct result DTO
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

    private void validatePostgreSqlDatabaseCorrectness(String userCode, String idempotencyKey, BigDecimal initialBalance, BigDecimal paymentAmount) {
        // Query payments table in PostgreSQL
        List<PaymentTransaction> transactions = paymentTransactionRepository.findAll().stream()
                .filter(tx -> idempotencyKey.equals(tx.getIdempotencyKey()))
                .toList();

        assertEquals(1, transactions.size(), "PostgreSQL MUST contain EXACTLY ONE transaction for the idempotency key!");

        PaymentTransaction tx = transactions.get(0);
        assertEquals(PaymentStatus.SUCCESS, tx.getStatus());
        assertEquals(userCode, tx.getUserId());
        assertEquals(0, paymentAmount.compareTo(tx.getAmount()));

        // Query user in PostgreSQL: balance must be deducted exactly once
        User updatedUser = userRepository.findByUserCode(userCode)
                .orElseThrow(() -> new AssertionError("User not found in PostgreSQL: " + userCode));
        BigDecimal expectedBalance = initialBalance.subtract(paymentAmount);
        assertEquals(
                0,
                expectedBalance.stripTrailingZeros().compareTo(updatedUser.getBalance().stripTrailingZeros()),
                String.format("PostgreSQL user balance mismatch! Expected %s but found %s", expectedBalance, updatedUser.getBalance())
        );

        // Query idempotency record in PostgreSQL
        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(idempotencyKey)
                .orElseThrow(() -> new AssertionError("Idempotency record not found in PostgreSQL: " + idempotencyKey));
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, record.getStatus());
        assertEquals(200, record.getHttpStatusCode());
        assertNotNull(record.getResponseBody());
        assertTrue(record.getResponseBody().contains(tx.getPaymentId()));
    }

    private double calculatePercentile(List<Double> sortedLatencies, double percentile) {
        if (sortedLatencies.isEmpty()) {
            return 0.0;
        }
        int n = sortedLatencies.size();
        int rank = (int) Math.ceil((percentile / 100.0) * n);
        int index = Math.max(0, Math.min(rank - 1, n - 1));
        return sortedLatencies.get(index);
    }

    private void printBenchmarkSummaryTable(List<BenchmarkResult> results) {
        System.out.println("=========================================================================================================");
        System.out.println(" APEXPAY POSTGRESQL 16 1,000-REQUEST CONCURRENCY BENCHMARK SUMMARY (CI-RUNNER PERFORMANCE)");
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
