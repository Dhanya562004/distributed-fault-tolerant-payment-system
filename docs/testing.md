# Test Strategy & Verification Evidence

**System**: Distributed Fault-Tolerant Payment System  
**Test Suite**: JUnit 5 + Mockito + Spring Boot Test + MockMvc + PostgreSQL 16 Testcontainers  
**Execution Status**: **51 Tests Passing (100% Success Rate)**  
- Fast H2 In-Memory Suite: 38 Tests Passed (0 Failures, 0 Errors, 0 Skipped)
- PostgreSQL 16 Testcontainers Suite: 13 Tests Passed (0 Failures, 0 Errors, 0 Skipped)
**CI/CD Pipeline**: GitHub Actions (All 3 Stages: Fast Tests, PostgreSQL 16 Suite, Packaging & Dashboard Verified)  
**Target Role**: Standard Chartered Development Engineer (Band 8)  

---

## 1. Testing Philosophy & Pyramid

Financial systems require uncompromising verification standards. Our testing methodology emphasizes high-concurrency invariants, boundary state transitions, 24-hour retention expiry, and fault tolerance recovery:

```mermaid
pie title Test Suite Distribution (51 Tests Total)
    "Idempotency Expiry & Replay Tests" : 11
    "PostgreSQL 16 Locking & Idempotency" : 13
    "REST Controller MockMvc Tests" : 10
    "Core Service Unit Tests" : 14
    "High-Concurrency Stress Benchmarks" : 2
    "Fault Tolerance & E2E Tests" : 2
```

---

## 2. Test Execution Summary

### Local Fast Execution (Docker-Free)
Running `mvn clean test` across the complete project locally on any developer machine (including Windows without Docker Desktop) produces:

- **Total Tests Executed**: 38
- **Passed**: 38
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0
- **Build Status**: **BUILD SUCCESS**

### Dual-Stage GitHub Actions Execution (CI/CD)
On GitHub Actions Ubuntu runners, both test suites are executed in strict isolation:
- **Stage A (Fast H2 Suite)**: `mvn clean test -B` (38 passed)
- **Stage B (PostgreSQL 16 Suite)**: `mvn test -Ppostgres-it -B` (13 passed)
- **Stage C (Packaging & Dashboard)**: JAR packaged and Streamlit validated
- **Overall Result**: **51 Tests Run, 51 Passed, 0 Failures, 0 Errors**

---

## 3. Test Suites & Coverage Breakdown

| Test Suite Class | Type | Tests | Key Capabilities Verified |
| :--- | :--- | :-: | :--- |
| `IdempotencyExpiryAndReplayTest` | Integration / SpringBootTest | 11 | 1. New key creates payment.<br>2. Duplicate request within 24h replays response.<br>3. Cached replay returns `Idempotent-Replayed: true`.<br>4. Fresh execution does not return replay header.<br>5. Payload mismatch on unexpired key rejected with 409.<br>6. Key expires exactly at configured 24h boundary.<br>7. Expired key can be reused for new payment.<br>8. Concurrent requests reusing expired key prevent double charges.<br>9. Expired-key cleanup preserves payment audit history.<br>10. Refund duplicate replay includes `Idempotent-Replayed: true`.<br>11. Concurrent duplicate requests returning 409 do not return replay header. |
| `PaymentControllerTest` | Spring MVC MockMvc | 5 | 1. `POST /api/v1/payments` returns 201 CREATED for sync payment.<br>2. Returns 409 CONFLICT on payload mismatch.<br>3. Returns `Idempotent-Replayed: true` header on cached payment.<br>4. Excludes replay header on original payment execution.<br>5. Supports pagination parameters (`page`, `size`). |
| `RefundControllerTest` | Spring MVC MockMvc | 5 | 1. `POST /api/v1/refunds` returns 201 CREATED.<br>2. Returns 422 UNPROCESSABLE_ENTITY on invalid state transition.<br>3. Returns `Idempotent-Replayed: true` header on cached refund.<br>4. Excludes replay header on original refund execution.<br>5. `GET /api/v1/refunds/{id}` returns 200 OK. |
| `IdempotencyServiceTest` | Unit / Mockito | 7 | 1. New key acquisition lock.<br>2. Concurrent in-progress request conflict.<br>3. Cached response return on identical replay.<br>4. Reused key with payload mismatch rejection (`PayloadMismatchException`).<br>5. Atomic acquisition of expired key via `updateIfExpired`.<br>6. Expired key race condition detection (IN_PROGRESS -> 409).<br>7. Purge of expired keys excluding IN_PROGRESS records. |
| `ConcurrentPaymentTest` | High-Concurrency Stress Benchmark | 1 | 1,000 concurrent duplicate requests across 3 isolated runs; bounded 50-worker pool; verifies exactly 1 charge processed, cached replays, 409 conflict handling, zero double-spending, and detailed p50/p95/p99 latency metrics. |
| `FaultToleranceRetryTest` | Fault Simulation | 1 | Injected gateway socket read timeouts; validates exponential backoff with jitter and successful recovery on subsequent attempt. |
| `PaymentProcessingServiceTest` | Unit / Mockito | 3 | 1. Sufficient balance payment authorization.<br>2. Insufficient balance fail-fast check (`InsufficientBalanceException` with 0 retries).<br>3. Collection pagination (`page`, `size`). |
| `RefundProcessingServiceTest` | Unit / Mockito | 4 | 1. Valid partial refund and balance restoration.<br>2. Rejection of refund on FAILED payments.<br>3. Prevention of cumulative refunds exceeding transaction amount.<br>4. Cached response on duplicate refund idempotency key. |
| `PaymentLifecycleIntegrationTest` | E2E Integration | 1 | Complete banking lifecycle: User creation -> Sync payment -> Idempotent replay with header -> Payload mismatch -> Input validation -> Payment retrieval -> Valid refund -> Over-refund rejection -> Audit trail verification. |
| `PostgreSqlIdempotencyTest` | Real PostgreSQL 16 Integration | 7 | 1. New key acquisition in PostgreSQL.<br>2. In-progress 409 conflict.<br>3. Cached replay without double deduction.<br>4. Payload mismatch rejection.<br>5. Concurrent duplicate prevention (1 charge, 0 double spend).<br>6. Expired key reuse creates new payment in PostgreSQL.<br>7. Concurrent expired-key reuse prevents double charges via row-level atomic locks. |
| `PostgreSqlOptimisticLockingTest` | Concurrency / Locking | 3 | Validates JPA `@Version` on `User` and `PaymentTransaction`. Stale entity updates trigger genuine `OptimisticLockingFailureException` in PostgreSQL, preventing lost updates. |
| `PostgreSqlPessimisticLockingTest` | Concurrency / Locking | 2 | Validates `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT FOR UPDATE`). Proves PostgreSQL lock manager blocks competing transactions until the lock holder commits. |
| `PostgreSqlConcurrencyStressTest` | High-Concurrency Stress Benchmark | 1 | 1,000 duplicate requests across 50 worker threads and 3 isolated runs against PostgreSQL 16, measuring latency percentiles (p50, p95, p99) and single-charge database persistence. |

---

## 4. Key Verified Invariants

### 4.1 Zero Double-Spending Under 1,000 Concurrent Duplicate Requests

`ConcurrentPaymentTest` bombards the payment engine with **1,000 duplicate requests sharing the same idempotency key and payload** across **3 isolated, repeatable benchmark runs**.

#### Concurrency & Benchmark Configuration
- **Total Requests**: 1,000 per run (3,000 requests total across 3 runs).
- **Worker Concurrency**: Bounded `ExecutorService` with 50 worker threads (`benchmark.workers=50`).
- **Coordination Mechanism**: Dual `CountDownLatch` barrier (`readyLatch` for thread warmup, `startLatch` for simultaneous microsecond release, `finishLatch` with 60-second safety timeout).
- **Timing Resolution**: `System.nanoTime()` for high-precision per-request latency tracking and batch throughput.
- **Percentile Calculation Method**: Nearest-rank discrete sample percentile method:
  $$\text{rank} = \left\lceil \frac{p}{100} \times N \right\rceil, \quad \text{index} = \max(0, \text{rank} - 1)$$

#### Execution Commands
```bash
# Execute the 1,000-request concurrency stress test independently
mvn test -Dtest=ConcurrentPaymentTest

# Execute the complete automated test suite
mvn clean test
```

#### Empirical Benchmark Results (Service-Level Performance)
```
=========================================================================================================
 APEXPAY 1,000-REQUEST CONCURRENCY BENCHMARK SUMMARY (SERVICE-LEVEL PERFORMANCE)
=========================================================================================================
Run    | Requests | Workers  | Throughput   | Total Time | Avg Latency  | p50 (Med)  | p95        | p99       
---------------------------------------------------------------------------------------------------------
#1     | 1000     | 50       |    554.7 rps |   1802.7 ms |   87.744 ms |    6.789 ms |  629.064 ms | 1708.116 ms
#2     | 1000     | 50       |    902.9 rps |   1107.6 ms |   48.808 ms |    4.274 ms |  344.781 ms |  377.274 ms
#3     | 1000     | 50       |   1527.6 rps |    654.6 ms |   28.120 ms |    2.062 ms |  166.966 ms |  404.663 ms
=========================================================================================================
```

#### Concurrency Outcome Breakdown (Per Run)
- **Original Charges Processed**: Exactly **1** per run (authorized with `PaymentStatus.SUCCESS`).
- **Cached Idempotent Replays**: Automatically served cached HTTP 200 payload once the primary transaction finished.
- **Expected In-Flight Duplicate Conflicts**: HTTP 409 (`DuplicateRequestException`) handled gracefully while primary transaction was in progress.
- **Unexpected Failures**: Exactly **0** unexpected errors or exceptions across all 3,000 requests.

#### Rigorous Database State Validation
Following each benchmark run, persisted database records are directly queried and validated:
1. **Single Payment Record**: Querying `payments` table confirms `count(idempotencyKey) == 1`.
2. **Single Balance Deduction**: User account balance decreased from $1,000.00 to $900.00 **exactly once** ($100.00 charge). **Zero double-spending**.
3. **Consistent Idempotency Key**: Querying `idempotency_keys` table confirms status `COMPLETED`, HTTP status code 200, and non-null serialized response payload referencing the payment ID.

#### Known Benchmark Scope & Limitations
- **Service-Level Performance**: Measurements represent in-process service execution latency. HTTP deserialization and network serialization are excluded.
- **In-Memory H2 vs. PostgreSQL**: Executed against H2 in PostgreSQL compatibility mode. Lock contention and latency profiles in H2 do not reflect distributed multi-node PostgreSQL over a physical network.

### 4.2 Fail-Fast Non-Retryable Error Handling
`PaymentProcessingServiceTest.testInitiatePayment_InsufficientBalance` validates that business errors do not waste retries.
- **Attempt Count**: Exactly 1 attempt.
- **Retry Count Recorded**: 0 retries.
- **Verification**: `verify(metricsService, never()).recordRetry()`.

### 4.3 Payload Mismatch Conflict Detection
`IdempotencyServiceTest.testTryAcquire_PayloadMismatch_ThrowsException` and `PaymentControllerTest.testInitiatePayment_PayloadMismatch_ReturnsConflict` verify that re-using an idempotency key with a different payload throws `PayloadMismatchException` and yields HTTP 409 Conflict.

### 4.4 Real PostgreSQL 16 Testcontainers Architecture
To bridge the fidelity gap between in-memory H2 and production PostgreSQL, ApexPay incorporates genuine PostgreSQL 16 integration testing:
- **Managed Singleton Container Lifecycle**: Implemented in `AbstractPostgreSqlIntegrationTest`, spin-up occurs once per JVM run, sharing the `postgres:16-alpine` container across test classes.
- **Dynamic Spring DataSource Binding**: `@DynamicPropertySource` dynamically injects the ephemeral mapped port and credentials without hardcoded configuration.
- **Zero-Docker Local Experience**: Local developers run `mvn clean test`, which excludes the PostgreSQL suite via `<test.exclude.pattern>**/postgres/**</test.exclude.pattern>`, completing in ~3 seconds.
- **Automated CI/CD Pipeline**: GitHub Actions executes the full test suite in two sequential stages:
  - **Stage A**: `mvn clean test` (Fast H2 tests)
  - **Stage B**: `mvn test -Ppostgres-it` (Real PostgreSQL 16 Testcontainers suite on Ubuntu runner)

### 4.5 PostgreSQL Optimistic Locking Verification (`@Version`)
`PostgreSqlOptimisticLockingTest` validates optimistic concurrency control on `User` (`version`) and `PaymentTransaction` (`lock_version`):
- Two independent transactions read the identical record at version 0.
- Transaction A updates the record and commits, incrementing the version in PostgreSQL to 1.
- Transaction B attempts to flush an update referencing the stale entity (version 0).
- Hibernate generates `UPDATE ... WHERE id = ? AND version = 0`, matching 0 rows in PostgreSQL.
- Spring Data JPA immediately raises `OptimisticLockingFailureException`.
- Database consistency is verified: Transaction A's state is preserved, and no lost update occurs.

### 4.6 PostgreSQL Pessimistic Row-Level Locking Verification (`SELECT FOR UPDATE`)
`PostgreSqlPessimisticLockingTest` verifies exclusive row locking via `@Lock(LockModeType.PESSIMISTIC_WRITE)`:
- Transaction A calls `userRepository.findByUserCodeForUpdate(userCode)`. PostgreSQL executes `SELECT ... FOR UPDATE`, holding an exclusive tuple lock.
- Transaction B concurrently calls `userRepository.findByUserCodeForUpdate(userCode)` on a separate thread.
- **Blocking Verification**: Transaction B is verified to be blocked by PostgreSQL's lock manager while Transaction A holds the lock.
- Once Transaction A updates the balance and commits, PostgreSQL releases the lock.
- Transaction B immediately unblocks, reads Transaction A's committed balance, and applies its update sequentially.
- Final user balance confirms strict serialization and zero lost updates.

### 4.7 24-Hour Idempotency Expiry, Retention Period & Replay Header Verification

Step 3 introduces temporal lifecycle management for idempotency tokens, verified via deterministic automated tests:

#### 1. Configurable Retention Period & Database Persistence
- Configured via `app.idempotency.retention-hours: 24` (defaults to 24 hours).
- Persisted directly in `idempotency_keys` table via `created_at` and `expires_at` timestamp columns (UTC server-side time).
- Backed by index `idx_idempotency_expires_at` for high-performance expiry querying.

#### 2. Deterministic Testing with Injectable `Clock`
- Uses `com.paymentsystem.config.TimeConfig` providing an injectable `java.time.Clock` bean.
- Test suites (`IdempotencyExpiryAndReplayTest`) utilize a thread-safe `MutableClock` to simulate precise time progression (e.g. advancing 12h, 23h 59m 59s, 24h, 25h) without error-prone `Thread.sleep`.

#### 3. `Idempotent-Replayed: true` Response Header
- When a payment or refund request is served from the completed idempotency cache within the retention window, the controller sets the HTTP response header:
  `Idempotent-Replayed: true`
- Fresh executions (first-time payment authorizations) do NOT include this header.
- Rejection responses (HTTP 409 Conflict for payload mismatch or in-flight duplicate) do NOT include this header.

#### 4. Atomic Concurrency-Safe Expired-Key Reuse
- Once a key passes its exact 24-hour expiry boundary ($T_0 + 24\text{h}$), it is eligible for reuse.
- `IdempotencyServiceImpl.acquireExpiredKey` executes an atomic database operation:
  `UPDATE IdempotencyKeyRecord i SET i.status = :newStatus, i.requestHash = :requestHash, i.responseBody = NULL, i.httpStatusCode = NULL, i.createdAt = :now, i.updatedAt = :now, i.expiresAt = :newExpiresAt WHERE i.keyValue = :keyValue AND i.expiresAt <= :now`
- Under PostgreSQL, row-level locking ensures only ONE thread can acquire and reuse the expired key. Competing concurrent threads observe the updated `IN_PROGRESS` state and are rejected with HTTP 409, preventing double-charging.
- Verified under high concurrency with 20 parallel threads in `IdempotencyExpiryAndReplayTest.test8_ConcurrentRequestsReusingExpiredKeyCannotDoubleCharge` and 15 threads in `PostgreSqlIdempotencyTest.testPostgreSqlConcurrentExpiredKeyReuse_PreventsDoubleCharge`.

#### 5. Audit & Transaction History Preservation
- The background scheduled task (`cleanupExpiredKeys`) purges expired records from `idempotency_keys` while strictly excluding active (`IN_PROGRESS`) transactions.
- Historical `PaymentTransaction` records and immutable NoSQL/MongoDB `audit_logs` are never deleted, ensuring an unbroken financial audit trail.
