# AI Usage Disclosure

## 1. Project Overview

**ApexPay** is an educational reference implementation of a distributed, fault-tolerant payment and transaction processing engine, focusing on transaction safety, concurrency control, and fault tolerance.

### Core Technology Stack
- **Backend Framework:** Java 17, Spring Boot 3.3.4, Spring Data JPA, Hibernate
- **Persistence Architecture:** Relational ACID storage (embedded H2 for local development/live demo; PostgreSQL 16 for integration testing and production configuration) with optional MongoDB document audit logging
- **Interactive Control Room:** Python 3.11, Streamlit, Plotly, Pandas
- **Build & Infrastructure:** Apache Maven 3.9+, Docker (multi-stage build), Docker Compose, GitHub Actions CI/CD

### Key Architectural Patterns
- **Distributed Cryptographic Idempotency:** SHA-256 request payload hashing, atomic database token acquisition, 24-hour retention expiry, atomic expired-key reuse, and `Idempotent-Replayed: true` HTTP response headers.
- **Transactional Outbox & Signed Webhooks:** Atomic commit of payment state and webhook event records in a single database transaction, HMAC-SHA256 signature verification, scheduled delivery worker with lease-based crash recovery, exponential backoff with full jitter, dead-letter queuing (DLQ), and SSRF validation.
- **Deterministic Keyset Pagination:** Cursor-based pagination with composite ordering (`createdAt DESC, id DESC`), URL-safe Base64 opaque cursors, HMAC-SHA256 cursor integrity signing, dynamic JPA Criteria seek queries without SQL `OFFSET`, and API key credential enforcement.
- **Resilient Concurrency & Error Classification:** Account-partitioned concurrency queues using consistent hashing (`Math.abs(userId.hashCode()) % 4`), pessimistic row-level locking (`SELECT FOR UPDATE`), JPA `@Version` optimistic locking, fail-fast classification for non-retryable business exceptions, and simulated banking gateway fault injection.

> **Operational Scope Note:** ApexPay is a software engineering reference and demonstration system. It does not process real monetary transactions, does not integrate with live financial rails or credit card payment networks, is not certified under PCI-DSS standards, and is not certified for production banking deployment.

---

## 2. AI Tools Used

### Antigravity (Google DeepMind)
- **Role:** AI-assisted software engineering and pair-programming assistant.
- **Scope of Use:** AI-assisted coding, debugging, automated test creation, documentation drafting, and CI/CD troubleshooting.

### ChatGPT (OpenAI)
- **Role:** AI conversational assistant.
- **Scope of Use:** Project planning, technical explanations, architecture discussions, debugging guidance, and documentation preparation.

The AI tools documented here are Antigravity and ChatGPT.

---

## 3. How AI Assisted Development

AI assistance was utilized interactively across multiple phases of the engineering lifecycle:

### Code Generation
- **Scaffolding Components:** Generated baseline implementations of Spring Boot controllers, entities, repository interfaces, and service layer implementations (`PaymentProcessingServiceImpl`, `RefundProcessingServiceImpl`, `IdempotencyServiceImpl`).
- **Pattern Implementations:** Assisted in implementing the Transactional Outbox pattern (`OutboxEvent`, `OutboxDeliveryWorker`), cryptographic cursor generation and verification (`CursorCodecService`), dynamic keyset pagination queries (`PaymentTransactionRepositoryImpl`), and SSRF protection checks (`WebhookSecurityValidator`).
- **Distinction Between Suggestions and Implementation:** AI suggestions served as initial drafts. Code was iteratively compiled, adjusted for framework compatibility, integrated into Maven, and validated through automated unit and integration tests.

### Debugging
- **PostgreSQL LOB Streaming Error:** Assisted in diagnosing and resolving PostgreSQL Large Object (OID) streaming exceptions when handling `@Lob` on `IdempotencyKeyRecord` by switching to explicit `columnDefinition = "TEXT"`.
- **Testcontainers Dynamic Port Shifting:** Assisted in identifying test isolation failures caused by dynamic port recycling across test classes, leading to the adoption of a static singleton container lifecycle pattern in `AbstractPostgreSqlIntegrationTest`.
- **Streamlit Datetime Parsing:** Assisted in debugging pandas `ValueError` exceptions caused by empty or malformed timestamps when fetching remote metrics on Streamlit Cloud.

### Automated Test Creation
- **Suite Authoring:** Assisted in generating comprehensive unit tests, MockMvc controller tests, and Testcontainers integration tests.
- **Edge Case Formulation:** Scaffolding test scenarios for payload hash collisions, in-flight duplicate lock contention, 24-hour key expiry boundary transitions, cumulative refund limit violations, IPv4-mapped IPv6 SSRF bypasses, and outbox worker lease recovery under simulated node failure.

### Documentation
- **Technical Specifications:** Assisted in structuring and writing architectural decision records (ADRs), system architecture specifications (`docs/architecture.md`), pagination specifications (`docs/pagination.md`), webhook delivery documentation (`docs/webhooks.md`), and the project `README.md`.

### CI Troubleshooting
- **Pipeline Configuration:** Assisted in designing the 3-stage GitHub Actions workflow (`.github/workflows/ci.yml`), configuring Maven Surefire profiles (`-Ppostgres-it`), setting test exclusion patterns (`**/postgres/**`) for Docker-free local runs, and setting up artifact archiving.

### Security Review
- **Static Code & Concurrency Review:** Assisted in conducting an evidence-based simulated security and concurrency audit (`docs/code-review.md`), identifying defects such as Spring proxy self-invocation on `@Transactional` methods, missing pessimistic write locks during cumulative refund evaluations, and potential SSRF bypasses with alternative IP encodings.

---

## 4. Human Contributions and Responsibilities

The human project owner directed and governed the development lifecycle:

### Human Responsibilities
- **Defined Project Requirements and Priorities:** Established system objectives, architectural scope, and functional milestones.
- **Directed AI-Assisted Development:** Prompted AI tools, guided architectural choices, specified design patterns (e.g., cryptographic idempotency, transactional outbox, keyset pagination), and steered implementation iterations.
- **Managed GitHub Repository Activities:** Managed repository activities, coordinated commits and pull requests, monitored GitHub Actions workflows, and maintained the main branch.
- **Reviewed Development Reports and CI Outcomes:** Monitored build outputs, evaluated test execution reports across H2 and PostgreSQL 16 Testcontainers stages, and verified CI run results in GitHub Actions.
- **Coordinated Deployment and Documentation:** Managed deployment configurations across Render (Spring Boot backend) and Streamlit Cloud (interactive dashboard), configured environment variables, and reviewed all technical documentation for accuracy.

### Scope Clarifications
- Implementation code, automated tests, and technical documentation were produced through iterative AI assistance rather than manual end-to-end coding from scratch.
- Commits, pull requests, and CI outcomes were reviewed via automated status checks and development reports rather than line-by-line manual code audits of every commit.
- Containerized PostgreSQL 16 integration tests were executed in the automated GitHub Actions CI environment rather than manual local Docker execution.
- Security properties were validated through automated test assertions, regression tests, and AI-assisted static review rather than formal external penetration testing or third-party audits.

---

## 5. Testing and Verification

### Reported Automated Test Results

The repository test suite consists of **232 total automated tests** across 35 test classes, structured into two execution tiers:

| Test Execution Stage | Environment | Test Count | Status | Verified Evidence |
| :--- | :--- | :---: | :---: | :--- |
| **Stage A: Fast Unit & Integration** | In-Memory H2 (Docker-Free) | 191 | **PASSED** (0 failures, 0 errors, 0 skipped) | Verified via `mvn clean test` (191 tests passed in 01:23 min). |
| **Stage B: Concurrency & Real Database** | PostgreSQL 16 Testcontainers (Docker) | 42 | **Configured** (Docker-dependent) | 42 tests across 8 classes (including double-entry ledger verification in `PostgreSqlLedgerIntegrationTest`, `PostgreSqlRefundConcurrencyTest`, `PostgreSqlIdempotencyTest`, and `PostgreSqlAtomicPaymentCommitIntegrationTest`). Requires running Docker daemon (`mvn test -Ppostgres-it`). |
| **Total Automated Suite** | Combined | **233** | **191 Passing locally (Docker-free), 42 PostgreSQL-ready** | Verified local H2 pass; containerized suite executes in CI with Docker. |

### Concurrency Stress Benchmark
- **Test Implementation:** `ConcurrentPaymentTest` (H2) and `PostgreSqlConcurrencyStressTest` (PostgreSQL 16).
- **Execution Profile:** 1,000 duplicate payment requests submitted concurrently using 50 worker threads coordinated by a `CountDownLatch` barrier.
- **Verified Assertions:** Exactly 1 transaction succeeded with balance deduction; 999 duplicate requests were rejected or served from cache; zero double-spending occurred.
- **Scope Limitation:** This benchmark executes in-process service-level calls and does not measure external network latency, HTTP serialization overhead, or distributed multi-node coordination.

### Automated Testing Disclaimer
Passing automated tests validates that the system satisfies its defined unit, integration, and concurrency assertions under synthetic test conditions. Automated test execution does **not** constitute formal production certification, does **not** prove the absence of undiscovered edge cases or vulnerabilities, and does **not** simulate live inter-bank clearing conditions.

---

## 6. Deployment Transparency

The active deployment topology utilizes decoupled free-tier cloud platforms:

```
[User / Browser]
       |
       | HTTPS (TLS 1.3)
       v
[Streamlit Community Cloud]  --> Python 3.11 Runtime (Interactive Dashboard)
       |
       | REST API (HTTPS)
       v
  [Render Web Service]        --> Spring Boot 3.3.4 (Alpine JRE Container)
       |
       +---> Live Persistence: Embedded In-Memory H2 Database (MODE=PostgreSQL)
       +---> Telemetry Endpoints: Actuator (/actuator/health) & Metrics (/api/v1/metrics)
       +---> Simulated Rails: In-Memory Banking Rails Simulator
```

### Actual Deployment Reality
1. **Spring Boot Backend:** Hosted as a free-tier web service on Render (`https://apexpay-backend-sc5s.onrender.com`). Free instances sleep after 15 minutes of inactivity, resulting in a 30–60 second cold start on initial access.
2. **Streamlit Dashboard:** Hosted on Streamlit Community Cloud (`https://distributed-fault-tolerant-payment-system-5qg2tjnkfrk7tvbqxzsz.streamlit.app/`), providing an interactive UI for metrics, transactions, and fault simulation.
3. **Live Database Storage:** The live Render backend operates using an **in-memory H2 database** (`jdbc:h2:mem:payments_db;MODE=PostgreSQL`). Data does not persist across container restarts or cold starts.
4. **PostgreSQL Validation:** Real PostgreSQL 16 is validated via Testcontainers in GitHub Actions CI; a production profile configuration exists in `application-prod.yml` but is **not** connected as the live database for the free public demo.
5. **Simulated Banking Gateway:** Downstream payment authorization rails are synthetic (`GatewaySimulationServiceImpl.java`), simulating latency, 503 outages, and timeouts for demonstration purposes.

---

## 7. AI Limitations and Verification

Transparency regarding AI-assisted development requires acknowledging key technical limitations:

- **Propensity for Subtle Defects:** AI coding assistants can generate code that appears structurally sound but contains subtle concurrency flaws (e.g., Spring AOP self-invocation bypassing `@Transactional`, or stale worker lease overwrites under distributed contention).
- **Mocking and Test Limitations:** AI-assisted test authoring may create tests that pass due to overly permissive mocks or synthetic assumptions rather than testing true runtime behavior. Rigorous containerized testing (such as PostgreSQL Testcontainers) is necessary to validate real database semantics.
- **Necessity of Human Oversight:** Human review, domain knowledge, and testing rigor remain essential to evaluate architectural decisions, enforce security boundaries, and ensure reliable execution.
- **Evidence-Based Technical Claims:** Technical metrics, complexity claims, and performance assertions must be backed by reproducible benchmark code and repository evidence rather than AI-generated estimates.

### Phase 2 AI Mistakes and Corrections Log
1. **Missing Package Import (`PaymentProcessingServiceImpl.java`)**:
   - *Mistake*: When injecting the newly created `RetryJitterPolicy` into `PaymentProcessingServiceImpl`, the import statement `import com.paymentsystem.service.RetryJitterPolicy;` was omitted because the service implementation resides in `com.paymentsystem.service.impl` while the policy interface/class is in `com.paymentsystem.service`.
   - *Impact*: Maven compilation failed with `cannot find symbol: class RetryJitterPolicy`.
   - *Correction*: Explicitly imported `RetryJitterPolicy` into `PaymentProcessingServiceImpl.java` and recompiled.
2. **Unhandled Checked Exception in Integration Test (`IdempotencyExpiryAndReplayTest.java`)**:
   - *Mistake*: Newly authored test method `test13_StuckKeyWithSuccessfulPayment_RecoveredAsCompletedWithoutDoubleCharge()` called `createTestUser(...)`, which declares checked `throws Exception`. The test method signature initially omitted `throws Exception`.
   - *Impact*: Maven test compilation failed with `unreported exception java.lang.Exception; must be caught or declared to be thrown`.
   - *Correction*: Added `throws Exception` to the test method signature.
3. **Queue Manager Multi-Instance Assumption (Item 3 Check)**:
   - *Clarification*: The README previously implied consistent hash queue partitioning was distributed across instances. Inspection confirmed `DistributedQueueManagerImpl` uses local in-memory `LinkedBlockingQueue` instances within a single JVM. Crash semantics mean any queued tasks in memory are lost, and per-user ordering cannot be maintained across multiple instances without an external broker. Updated docs and README Known Limits to accurately reflect this single-instance architectural limit.
4. **Money Representation Minor Units Migration (Item 4)**:
   - *Architecture*: Replaced `NUMERIC(19,4)` / `BigDecimal` with integer minor units (`BIGINT` in SQL, `Long` in Java), eliminating all floating-point rounding hazards. Added `currencies` table and `CurrencyService` tracking decimal precision (`USD`, `EUR`, `GBP`, `INR` = 2).
   - *Mistake*: In `TransactionalOutboxIntegrationTest.java` and PostgreSQL Testcontainers classes, leftover unused `import java.math.BigDecimal;` imports and string-based comparisons (`0 == new BigDecimal("...").compareTo(...)`) initially lingered after converting DTOs.
   - *Correction*: Systematically removed all `BigDecimal` references across all 96 source files and 28 test files, adopting primitive `long` equality and arithmetic checks (`assertEquals(expectedLong, actualLong)`). Added 5 unit tests in `CurrencyServiceTest` to verify currency retrieval, decimal place lookup, and ISO validation. All 134 fast tests pass.

### Phase 3 AI Mistakes and Corrections Log
1. **Unregistered Jackson JavaTimeModule in `ApiKeyAuthenticationFilter`**:
   - *Mistake*: When configuring `ApiKeyAuthenticationFilter` with standalone Jackson `ObjectMapper`, `findAndRegisterModules()` was not invoked, resulting in `InvalidDefinitionException: Java 8 date/time type java.time.Instant not supported by default` when serializing `ErrorResponse` on unauthenticated requests.
   - *Correction*: Configured `objectMapper.copy().findAndRegisterModules()` in `ApiKeyAuthenticationFilter` and `new ObjectMapper().findAndRegisterModules()` in `ApiKeyAuthenticationTest`.
2. **Missing `created_at` in Seed `data.sql` for API Keys**:
   - *Mistake*: Hibernate mapped `@Column(name = "created_at", nullable = false)` on `ApiKey` entity without default in generated DDL, causing H2 initialization failure `NULL not allowed for column "CREATED_AT"` on `INSERT INTO api_keys`.
   - *Correction*: Added `created_at` with `CURRENT_TIMESTAMP` to all seed `api_keys` records in `data.sql`.
3. **Idempotency Key Scoping Discrepancy in `IdempotencyExpiryAndReplayTest`**:
   - *Mistake*: `test11` directly persisted an in-progress record using the default entity constructor (setting `api_key = "default"`), while the test client submitted HTTP requests authenticated with `X-Api-Key: apexpay_admin_secret_key_2026`. Because Phase 3 strictly scopes idempotency keys per API key `(api_key, key_value)`, the in-progress record for `"default"` did not block `"apexpay_admin_secret_key_2026"`, causing the endpoint to process as a new request (returning 201 instead of 409).
   - *Correction*: Updated test 11 to persist the in-progress key with the matching `API_KEY`, correctly validating that concurrent requests under the same tenant API key receive HTTP 409 Conflict.
4. **MockMvc Authentication Rejection in Integration Tests**:
   - *Mistake*: Adding `ApiKeyAuthenticationFilter` across the entire application context caused unauthenticated MockMvc requests in `PaymentLifecycleIntegrationTest`, `TransactionalOutboxIntegrationTest`, and `IdempotencyExpiryAndReplayTest` to return 401 Unauthorized.
   - *Correction*: Updated test suites to explicitly supply `X-Api-Key: apexpay_admin_secret_key_2026` across all integration requests, while verifying `X-Request-Id` headers and standardized error envelopes. All 143 fast tests pass with 0 errors.

### Phase 4 PR 1A AI Mistakes and Corrections Log
1. **Missing Package Import (`PaymentTransactionCoordinator.java`)**:
   - *Mistake*: When implementing `PaymentTransactionCoordinator`, the import statement `import java.util.Optional;` was omitted.
   - *Impact*: Maven compilation failed with `cannot find symbol: class Optional`.
   - *Correction*: Added `import java.util.Optional;` to `PaymentTransactionCoordinator.java`.
2. **Missing Entity Import in Test Classes (`PaymentProcessingServiceTest.java`, `FaultToleranceRetryTest.java`)**:
   - *Mistake*: Test refactoring in `PaymentProcessingServiceTest` and `FaultToleranceRetryTest` referenced `PaymentTransaction` in mock answers without an explicit import.
   - *Impact*: Compilation failed with `cannot find symbol: class PaymentTransaction`.
   - *Correction*: Added `import com.paymentsystem.entity.PaymentTransaction;` to both test classes.
3. **Unstubbed Mockito Repository Reads in Unit Tests**:
   - *Mistake*: Refactored `PaymentProcessingServiceImpl` calls `paymentCoordinator.executePaymentAttempt`, which loads the payment row using `paymentRepository.findByPaymentId(...)` and `findByPaymentIdForUpdate(...)`. Existing unit test mocks only stubbed `save(...)` and `saveAndFlush(...)`, causing `ResourceNotFound: Payment not found for ID`.
   - *Correction*: Added lenient stubs for `findByPaymentId` and `findByPaymentIdForUpdate` returning the tracked `PaymentTransaction` instance in mock setups.
4. **Retry Count Off-By-One on Business Failure**:
   - *Mistake*: In `PaymentProcessingServiceImpl`, on fail-fast business errors (such as insufficient funds), `attempts` (which was 1) was directly passed as `retryCount`, causing `testInitiatePayment_InsufficientBalance` to assert `expected: <0> but was: <1>`.
   - *Correction*: Properly computed `retryCount` as `Math.max(0, attempts - 1)`.
5. **JPA Pessimistic Write Lock Outside Active Transaction in Async Worker**:
   - *Mistake*: In `processWorkerMessage`, an initial idempotency / status check called `paymentRepository.findByPaymentIdForUpdate(...)` before delegating to the transaction coordinator. Because `processWorkerMessage` does not carry an outer `@Transactional` annotation (to prevent holding open database connections during retry delays), Hibernate threw `InvalidDataAccessApiUsageException: Query requires transaction be in progress`.
   - *Correction*: Switched the non-transactional pre-check to read via non-locking `paymentRepository.findByPaymentId(...)`, letting the pessimistic row lock `SELECT FOR UPDATE` execute inside `PaymentTransactionCoordinator.executePaymentAttempt` at the start of the attempt transaction.
6. **Payload Hash Mismatch in Crash Recovery Replay Test**:
   - *Mistake*: In `AtomicPaymentCommitIntegrationTest`, `testCrashBetweenPaymentCommitAndKeyUpdate` created a manual idempotency key with a hardcoded `"hash123"` payload hash. When replaying the request via MockMvc, `idempotencyService` computed the real SHA-256 hash of the request body and detected a mismatch, returning HTTP 409 `PAYLOAD_MISMATCH` instead of HTTP 201 cached replay.
   - *Correction*: Calculated the expected hash using `idempotencyService.computePayloadHash(payReq)` when seeding the key record.
7. **Gateway Transient Failure Simulation Race in Retry Test**:
   - *Mistake*: `testAttempt1FailsAfterDeduction_Attempt2Succeeds_BalanceDeductedOnce` initially used a background thread with `Thread.sleep(600)` to reset gateway behavior, creating timing sensitivity across varying machine load.
   - *Correction*: Replaced asynchronous timing with a deterministic Mockito spy: `doThrow(new ServiceUnavailableException(...)).doNothing().when(gatewaySimulationService).simulateGatewayCall(...)`.

### Phase 4 PR 1B AI Mistakes and Corrections Log
1. **JPA EntityManager Clearing Side Effect (`IdempotencyServiceImpl.java`)**:
   - *Mistake*: In `IdempotencyServiceImpl.java`, `idempotencyKeyRepository.updateStuckKeyConditionally` carries `@Modifying(clearAutomatically = true)`. Calling `paymentRepository.save(tx)` before this query left `tx` unflushed in the session; when Spring Data JPA ran the modifying query, `clearAutomatically = true` cleared the Hibernate L1 cache, discarding pending unwritten updates to `payments`.
   - *Impact*: In `PaymentRecoveryRaceIntegrationTest`, the stuck payment remained in `PROCESSING` rather than transitioning to `FAILED`.
   - *Correction*: Replaced `paymentRepository.save(tx)` with `paymentRepository.saveAndFlush(tx)` in `IdempotencyServiceImpl.java`, immediately flushing the update to SQL before the session clear occurs, and called `entityManager.clear()` in test before DB reload assertion.
2. **Missing Imports in `PaymentLifecycleIntegrationTest.java` and `PaymentTransactionCoordinator.java`**:
   - *Mistake*: After refactoring `PaymentTransaction` and deleting `setStatus()`, missing imports for `PaymentTransactionRepository` in `PaymentLifecycleIntegrationTest` and `InvalidTransactionStateException` in `PaymentTransactionCoordinator` caused compile-time errors.
   - *Correction*: Added explicit imports for the required classes.
3. **Exception Disambiguation & Assertion Alignment**:
   - *Mistake*: In concurrent refund tests, attempting to refund when cumulative limit is reached was initially expected to throw `InvalidTransactionStateException`, whereas business logic rightly distinguished `CumulativeRefundLimitExceededException` (HTTP 422 `CUMULATIVE_REFUND_LIMIT_EXCEEDED`) for partial over-refund vs `InvalidTransactionStateException` (HTTP 422 `INVALID_TRANSACTION_STATE`) for refunding an already `REFUNDED` payment.
   - *Correction*: Structured distinct exception handling in `GlobalExceptionHandler` and aligned test assertions in both unit and integration tests.

### Phase 4 PR 2 AI Mistakes and Corrections Log
1. **Un-annotated Multiple Constructors in Spring Beans (`LedgerReconciliationServiceImpl.java`, `LedgerServiceImpl.java`)**:
   - *Mistake*: Creating multiple overloaded constructors (for testing vs production) without explicit `@Autowired` caused Spring Boot container initialization failure: `Failed to instantiate: No default constructor found`.
   - *Correction*: Added `@Autowired` to the canonical constructors in both service implementations.
2. **DTO Class Naming & Return Type Discrepancies in Integration Tests**:
   - *Mistake*: In `LedgerDoubleEntryIntegrationTest`, tests initially referenced `RefundRequest` instead of `ProcessRefundRequest` and attempted to assign `paymentProcessingService.initiatePayment(...)` to `PaymentTransaction` rather than `PaymentResponse`.
   - *Correction*: Aligned types with `ProcessRefundRequest` and `PaymentResponse`.
3. **Shared Database State Contamination in Fast Integration Test**:
   - *Mistake*: `testDuplicateRetry_UniqueConstraintPreventsDuplicateLedgerRows` and `testAppendOnly_UpdateAndDeleteProhibited` inserted isolated `DEBIT` entries directly against `USR_ALICE` without an offsetting `CREDIT`. Because Spring integration tests share the persistence context across methods, subsequent tests ran into intentional reconciliation failures (`[RECONCILIATION MISMATCH] Currency USD imbalance detected! Diff: 6000`).
   - *Correction*: Used balanced test transactions and cleaned up direct test fixture inserts inside `finally` blocks, restoring clean state for subsequent tests. All 190 tests pass with 0 errors.

### Phase 5 AI Mistakes and Corrections Log
1. **H2 Dialect Parser Rejection of PostgreSQL `ON CONFLICT` Syntax in Flyway `V2` Seed Migration**:
   - *Mistake*: In `V2__seed_initial_data.sql`, initial seed statements used PostgreSQL dialect syntax `ON CONFLICT (col) DO NOTHING`. In local test runs using embedded H2 in PostgreSQL compatibility mode, the H2 SQL parser failed with `JdbcSQLSyntaxErrorException: Syntax error in SQL statement ... ON CONFLICT (code) DO NOTHING [42000-224]`.
   - *Correction*: Replaced `ON CONFLICT DO NOTHING` with clean ANSI SQL `INSERT INTO ... VALUES (...)`. Because Flyway tracks applied scripts in `flyway_schema_history` and guarantees strict once-per-database execution, standard ANSI SQL inserts are completely safe and execute identically across H2 and PostgreSQL 16.
2. **Cross-Test Database Pollution & Ledger Discrepancies from In-Memory State**:
   - *Mistake*: In `LedgerFeatureFlagIntegrationTest`, testing payment processing with `app.ledger.enabled=false` deducted test user balances without writing ledger entries, leaving an unbacked balance deduction in the shared in-memory H2 database. Furthermore, older tests (`AtomicPaymentCommitIntegrationTest`, `ConcurrentPaymentTest`, `RefundConcurrencyIntegrationTest`) dynamically instantiated users via `userRepository.save()` rather than `UserController.createUser()`, resulting in missing opening-balance records when later payments were processed.
   - *Impact*: In the full test suite run, `LedgerDoubleEntryIntegrationTest` reported reconciliation mismatches (`Table Balance <> Ledger Sum`).
   - *Correction*: Added idempotent opening balance recording in `PaymentTransactionCoordinator` to ensure any dynamically created user receives a balanced opening-balance record before its first payment balance deduction, added cleanup `finally` blocks to restore test user balances in `LedgerFeatureFlagIntegrationTest` and `IdempotencyExpiryAndReplayTest`, and ensured `user` entity reloads avoid optimistic lock exceptions. All 191 tests pass with 0 errors.

---

## 8. Final Disclosure

ApexPay was developed as an educational, architectural demonstration of resilient payment processing patterns, built with interactive assistance from AI engineering tools (Antigravity and ChatGPT) and guided by human requirements definition, direction, and automated verification. 

While the codebase incorporates rigorous distributed systems patterns—including cryptographic idempotency, transactional outbox delivery, keyset pagination, and pessimistic locking—it remains an architectural reference implementation. It is not an enterprise banking product, is not certified for PCI-DSS compliance, does not process real financial assets, and should not be deployed in production financial environments without independent professional security audits and infrastructure hardening.
