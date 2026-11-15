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

The repository test suite consists of **167 total automated tests** across 24 test classes, structured into two execution tiers:

| Test Execution Stage | Environment | Test Count | Status | Verified Evidence |
| :--- | :--- | :---: | :---: | :--- |
| **Stage A: Fast Unit & Integration** | In-Memory H2 (Docker-Free) | 129 | **PASSED** (0 failures, 0 errors, 0 skipped) | Verified via `mvn clean test` (129 tests passed in 01:12 min). |
| **Stage B: Concurrency & Real Database** | PostgreSQL 16 Testcontainers (Docker) | 38 | **Configured** (Docker-dependent) | 38 tests across 6 classes (including 3 new stuck-key recovery tests in `PostgreSqlIdempotencyTest`). Requires running Docker daemon (`mvn test -Ppostgres-it`). |
| **Total Automated Suite** | Combined | **167** | **129 Passing locally (Docker-free), 38 PostgreSQL-ready** | Verified local H2 pass; containerized suite executes in CI with Docker. |

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

---

## 8. Final Disclosure

ApexPay was developed as an educational, architectural demonstration of resilient payment processing patterns, built with interactive assistance from AI engineering tools (Antigravity and ChatGPT) and guided by human requirements definition, direction, and automated verification. 

While the codebase incorporates rigorous distributed systems patterns—including cryptographic idempotency, transactional outbox delivery, keyset pagination, and pessimistic locking—it remains an architectural reference implementation. It is not an enterprise banking product, is not certified for PCI-DSS compliance, does not process real financial assets, and should not be deployed in production financial environments without independent professional security audits and infrastructure hardening.
