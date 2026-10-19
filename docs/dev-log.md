# Development & Engineering Log

**Project**: Distributed Fault-Tolerant Payment System  
**Objective**: Standard Chartered Development Engineer (Band 8) Upgrade Mission  
**Date**: October 10, 2026  

---

## Entry 1: Repository Audit & Baseline Verification
- **Activity**: Inspected full repository structure, package declarations, Spring Boot starters, Maven pom.xml, and existing tests.
- **Action**: Ran baseline test suite using `mvn clean test`.
- **Result**: 7 baseline tests passed in 40.9s.
- **Findings**:
  - `PaymentProcessingServiceImpl` retried non-retryable business errors (`Insufficient user account balance`) 3 times with exponential backoff.
  - `IdempotencyServiceImpl` did not compare payload hashes on existing idempotency keys, allowing key reuse with conflicting payloads.
  - `RefundProcessingServiceImpl` ignored the return value of `idempotencyService.tryAcquireOrGet(...)`, failing to return cached responses on duplicate refunds.
  - Cumulative refund validation was missing; only single refund amounts were checked against the payment total.
  - Collection endpoints lacked pagination parameters (`page`, `size`).
- **Deliverable**: Generated `docs/initial-audit.md` and `docs/requirements-matrix.md`.

---

## Entry 2: Exception Hierarchy & Taxonomy Implementation
- **Activity**: Designed clean domain exception hierarchy to cleanly separate non-retryable business failures from retryable infrastructure outages.
- **Files Created**:
  - `com.paymentsystem.exception.NonRetryableException`
  - `com.paymentsystem.exception.RetryableException`
  - `com.paymentsystem.exception.InsufficientBalanceException`
  - `com.paymentsystem.exception.PayloadMismatchException`
  - `com.paymentsystem.exception.GatewayTimeoutException`
  - `com.paymentsystem.exception.ServiceUnavailableException`
  - `com.paymentsystem.exception.LockAcquisitionException`
- **File Updated**: `GlobalExceptionHandler.java` (added specific handlers for `PayloadMismatchException` [HTTP 409] and `InsufficientBalanceException` [HTTP 422]).

---

## Entry 3: Idempotency Payload Hash Conflict Enforcement
- **Activity**: Updated `IdempotencyServiceImpl.java` to inspect `existing.getRequestHash()` when a key hit is detected.
- **Behavior**: If `existing.getRequestHash()` does not match the incoming SHA-256 hash, immediately raise `PayloadMismatchException`.
- **Testing**: Added `testTryAcquire_PayloadMismatch_ThrowsException` in `IdempotencyServiceTest.java`.

---

## Entry 4: Fail-Fast Retry Loop & Gateway Simulation Refactoring
- **Activity**: Updated `PaymentProcessingServiceImpl.java` to break out of the retry loop immediately upon encountering any `NonRetryableException` (e.g., `InsufficientBalanceException`), leaving `retryCount = 0`.
- **Activity**: Updated `GatewaySimulationServiceImpl.java` to throw `GatewayTimeoutException` (transient timeout) and `ServiceUnavailableException` (503 outage) to cleanly test retry loops.

---

## Entry 5: Refund Correctness & Cumulative Refund Limit Enforcement
- **Activity**: Enhanced `RefundProcessingServiceImpl.java`:
  - Handled cached idempotency records and deserialized `RefundResponse` via `ObjectMapper`.
  - Added cumulative refund calculation: $\sum \text{prior refunds} + \text{current refund} \le \text{payment amount}$.
  - Transitioned payment status to `REFUNDED` when total refund equals payment amount.
- **Testing**: Created `RefundProcessingServiceTest.java` with 4 test cases (success, failed payment rejection, cumulative limit violation, and cached duplicate response).

---

## Entry 6: REST API Pagination & Documentation
- **Activity**: Added paginated overloads `getAllPayments(int page, int size)` and `getPaymentsByUser(String userId, int page, int size)` to `PaymentProcessingService` and `PaymentProcessingServiceImpl`.
- **Activity**: Updated `PaymentController.java` to expose `@RequestParam(defaultValue = "0") int page` and `@RequestParam(defaultValue = "20") int size`.
- **Deliverable**: Generated `docs/api-guide.md` and `src/main/resources/schema.sql`.

---

## Entry 7: Comprehensive Test Suite Expansion & End-to-End Lifecycle Verification
- **Activity**: Added `PaymentControllerTest.java`, `RefundControllerTest.java`, and `PaymentLifecycleIntegrationTest.java`.
- **Action**: Ran `mvn clean test`.
- **Result**: All 20 tests passed cleanly with 0 failures and 0 errors in 31.2s.

---

## Entry 8: Docker, Environment Configuration & CI Hardening
- **Activity**: Added `.env.example` with template configurations for PostgreSQL, MongoDB, Spring Boot, and Streamlit.
- **Activity**: Verified `docker-compose.yml` health checks and startup conditions.
- **Activity**: Verified GitHub Actions workflow `.github/workflows/ci.yml`.

---

## Entry 9: Transactional Outbox Pattern & Reliable Webhook Delivery Engine (Step 4)
- **Activity**: Designed and implemented the Transactional Outbox pattern to guarantee at-least-once, tamper-proof webhook notifications for payment and refund state transitions without dual-write hazards.
- **Components Created**:
  - `com.paymentsystem.entity.OutboxEvent` & `com.paymentsystem.entity.OutboxStatus` (`PENDING`, `PROCESSING`, `DELIVERED`, `DEAD_LETTER`).
  - `com.paymentsystem.repository.OutboxEventRepository` with atomic row-level claiming (`claimEvent`) and candidate query.
  - `com.paymentsystem.service.PaymentTransactionCoordinator`: Atomic commit of payment/refund state + outbox event within a single database transaction.
  - `com.paymentsystem.service.WebhookSignatureService`: HMAC-SHA256 signature generator (`v1=...`) with canonical timestamp prefix and constant-time verification.
  - `com.paymentsystem.service.WebhookSecurityValidator`: Strict SSRF protection blocking private IPs, loopback, and cloud metadata IP (169.254.169.254).
  - `com.paymentsystem.service.WebhookRetryPolicy`: Exponential backoff with random jitter formula and HTTP status classification (429/5xx retryable, 4xx permanent).
  - `com.paymentsystem.worker.OutboxDeliveryWorker`: Scheduled polling, lease management, worker crash recovery, zero open DB transactions during HTTP I/O.
- **Deliverables**: Generated `docs/webhooks.md`, updated `src/main/resources/schema.sql`, updated YAML configuration across environments.
- **Testing**: Added 6 comprehensive test suites:
  - `WebhookRetryPolicyTest` (6 tests)
  - `WebhookSecurityValidatorTest` (7 tests)
  - `WebhookSignatureServiceTest` (10 tests)
  - `OutboxDeliveryWorkerTest` (10 tests)
  - `TransactionalOutboxIntegrationTest` (6 tests)
  - `PostgreSqlTransactionalOutboxIntegrationTest` (10 tests)
- **Action**: Ran `mvn clean test`.
- **Result**: All 78 fast H2 & unit/integration tests passed cleanly (100% success rate); PostgreSQL 16 Testcontainers suite has 23 tests, bringing system total to 101 tests.

### Step 5: Production-Style Cursor-Based Payment Transaction Pagination with Keyset Optimization & Cryptographic Cursors
- **Date**: October 19, 2026
- **Scope**: Implemented deterministic keyset pagination for payment transactions with composite ordering (`createdAt DESC, id DESC`), URL-safe Base64 opaque cursors, HMAC-SHA256 signature signing, filter binding, $O(1)$ database query optimization without SQL `OFFSET`, and comprehensive test suites.
- **Key Deliverables**:
  - `PaymentPaginationService`: Core business logic for limit+1 pagination, filter validation, and entity-to-DTO projection.
  - `CursorCodecService`: High-security HMAC-SHA256 cursor generation, constant-time validation (`MessageDigest.isEqual`), SHA-256 filter fingerprinting, and tamper resistance.
  - `PaymentPaginationSecurityService`: API key and Bearer token credential guard for protected financial endpoints.
  - `PaymentTransactionRepositoryCustom` & `PaymentTransactionRepositoryImpl`: Dynamic JPA Criteria keyset query execution without `OFFSET` or count overhead.
  - `idx_payments_created_at_id`: Database composite index in `schema.sql` and `PaymentTransaction` JPA entity.
  - `docs/pagination.md`: Comprehensive specification, query optimization, security model, and error catalog.
- **Testing**:
  - `CursorCodecServiceTest` (10 unit tests)
  - `PaymentPaginationControllerTest` (9 MockMvc tests)
  - `PaymentPaginationIntegrationTest` (10 H2 integration tests covering all 27 required scenarios)
  - `PostgreSqlPaginationIntegrationTest` (12 real PostgreSQL 16 Testcontainers tests)
- **Results**: Local execution of `mvn clean test` runs 111 tests with 0 failures, 0 errors, and 0 skipped (100% success rate). GitHub Actions CI pipeline [Run 39263927415](https://github.com/Dhanya562004/distributed-fault-tolerant-payment-system/actions/runs/39263927415) passed across all 3 stages: Stage A (111 fast tests), Stage B (35 PostgreSQL 16 Testcontainers tests), and Stage C (Packaging & Dashboard Verification). Total system tests: 146/146 (100% pass rate).

---

### Step 6: Independent Code Review, Security Audit, Concurrency Hardening & Production Readiness
- **Date**: October 19, 2026
- **Scope**: Skeptical, evidence-based technical code review, vulnerability assessment, concurrency race condition analysis, and production readiness evaluation.
- **Key Defects Addressed & Remediated**:
  - `SEC-001` (CRITICAL): Eliminated self-invocation bypass of `@Transactional` in `PaymentProcessingServiceImpl` by executing each gateway attempt within an isolated `TransactionTemplate` programmatic boundary. Prevented uncommitted balance deduction duplication during retry loops.
  - `CONC-001` (HIGH): Fixed concurrent double-refund race condition in `RefundProcessingServiceImpl` by enforcing pessimistic row-level locking (`findByPaymentIdForUpdate` and `findByUserCodeForUpdate`) before evaluating cumulative refund ceilings.
  - `CONC-002` (HIGH): Hardened `OutboxDeliveryWorker` against stale worker lease expiration overwriting active worker leases or reverting `DELIVERED` status.
  - `SEC-002` (HIGH): Fixed SSRF bypasses in `WebhookSecurityValidator` by blocking IPv4-mapped IPv6 loopback/cloud metadata (`::ffff:127.0.0.1`, `::ffff:169.254.169.254`), IPv6 Unique Local Addresses (`fc00::/7`), IPv6 loopback (`::1`), link-local (`fe80::/10`), and Carrier-Grade NAT (`100.64.0.0/10`).
  - `SEC-003` (MEDIUM): In `CursorCodecService`, eliminated insecure fallback to static development HMAC key in production by auto-generating an ephemeral 256-bit runtime secret when unconfigured.
  - `ARCH-001` (MEDIUM): Documented multi-tenancy trust boundary and single administrative API key scope in `docs/code-review.md` and `docs/pagination.md`.
  - `DOC-001` / `DOC-002` (LOW): Corrected $O(1)$ complexity claims to $O(\log N + K)$ index seek complexity and clarified at-least-once webhook delivery semantics.
- **Testing & Verification**:
  - Added 7 regression tests across `WebhookSecurityValidatorTest` (+3), `OutboxDeliveryWorkerTest` (+2), `RefundProcessingServiceTest` (+1), and `PaymentLifecycleIntegrationTest` (+1).
  - Fast H2 test suite expanded from 111 to 118 tests; total automated test suite expanded to 153 tests (118 H2 + 35 PostgreSQL).
  - Local `mvn test` executed cleanly with 118 passed, 0 failures, 0 errors, 0 skipped.
- **Deliverables**: Created comprehensive `docs/code-review.md` and updated `docs/pagination.md`, `docs/webhooks.md`, `docs/testing.md`, `README.md`, and `docs/final-verification.md`.
