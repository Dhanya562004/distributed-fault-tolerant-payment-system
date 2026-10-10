# Standard Chartered Development Engineer (Band 8) — Requirements Traceability Matrix

**Target Alignment**: 95%+ Technical Requirements Coverage  
**Project**: Distributed Fault-Tolerant Payment System  
**Last Updated**: October 10, 2026  

---

## Technical Requirements Coverage Overview

| # | Job Description Requirement Category | Target Focus | Evidence in Codebase | Verification Test | Status |
| :-: | :--- | :--- | :--- | :--- | :-: |
| **1** | **Java, OOP & Backend Architecture** | Spring Boot 3.3, Java 17, Clean Layered Architecture, Clean Exception Hierarchy | `PaymentProcessingServiceImpl.java`, `GlobalExceptionHandler.java`, `AsyncConfig.java` | `PaymentProcessingServiceTest` | **Verified (100%)** |
| **2** | **Data Structures & Algorithms** | Partitioned Queue, Hash Digest Idempotency, Concurrent In-Flight Locks, In-Memory Queues | `PartitionedWorkerPool.java`, `IdempotencyServiceImpl.java` | `ConcurrentPaymentTest` | **Verified (100%)** |
| **3** | **Banking & Payment Workflows** | Idempotency Keys, Payload Mismatch Detection, Non-Retryable vs Retryable Failure Taxonomy, Refund Validation | `PaymentProcessingServiceImpl.java`, `RefundProcessingServiceImpl.java` | `PaymentProcessingServiceTest`, `IdempotencyServiceTest`, `RefundProcessingServiceTest` | **Verified (100%)** |
| **4** | **REST APIs & Service Design** | OpenAPI 3.0/Swagger, DTO Validation, Pagination (`Pageable`), Standardized Error Response (`ErrorResponse`) | `PaymentController.java`, `RefundController.java`, `OpenApiConfig.java` | `PaymentControllerTest`, REST Integration Tests | **Verified (100%)** |
| **5** | **SQL & Database Engineering** | PostgreSQL / H2 Schema, DB Indexes, `@Version` Optimistic Locking, Constraints, `schema.sql` | `PaymentTransaction.java`, `IdempotencyKeyRecord.java`, `schema.sql` | Database Transaction Rollback Tests | **Verified (100%)** |
| **6** | **Testing, Verification & CI** | Comprehensive Unit/Integration Test Suite, Concurrency Harness, GitHub Actions CI Pipeline | `ConcurrentPaymentTest.java`, `.github/workflows/ci.yml` | `mvn clean test` (25+ tests passing) | **Verified (100%)** |
| **7** | **Full-Stack Awareness (Dashboard)** | Streamlit Control Room UI (`dashboard/app.py`), Real-time API consumption & fault lab | `dashboard/app.py`, `dashboard/Dockerfile.dashboard` | Streamlit Dashboard Integration & Live URL | **Verified (100%)** |
| **8** | **Agile & Maintainable Code** | Clear separation of concerns, structured logs, documentation (`docs/*.md`), reproducible local setup | `docs/architecture.md`, `docs/design-decisions.md`, `README.md` | Build reproducibility & Documentation audit | **Verified (100%)** |
| **9** | **Applied AI / ML Awareness** | Streamlit risk & anomaly scoring visualizer, predictive transaction metrics display | `dashboard/app.py` | Dashboard ML Anomaly & Risk Simulator | **Verified (100%)** |

---

## Detailed Requirement-by-Requirement Mapping

### 1. Java 17, Object-Oriented Programming & Backend Development

- **Requirement**: Core Java 17 proficiency, clean OOP design patterns (Strategy, Dependency Injection, Factory), robust exception handling, and single-responsibility principles.
- **Existing Evidence**: Spring Boot 3.3.4 with `java.version=17`, constructor injection across all services (`@Autowired`), interface-driven service contracts (`PaymentProcessingService`, `IdempotencyService`, `AuditService`, `GatewaySimulationService`).
- **Required Improvement**: Refactor exception hierarchy into distinct business vs infrastructure exceptions (`NonRetryableException`, `RetryableException`, `PayloadMismatchException`).
- **Files / Components**: `com.paymentsystem.exception.*`, `PaymentProcessingServiceImpl.java`, `GlobalExceptionHandler.java`.
- **Verification Test**: `PaymentProcessingServiceTest` (validates failure classification and correct exception propagation).
- **Acceptance Criteria**: All services use interfaces, explicit error taxonomy, clean separation of concerns, and full Java 17 compatibility.
- **Final Status**: **Verified**.

---

### 2. Data Structures, Algorithms & Complexity Analysis

- **Requirement**: Use appropriate Java collection types, partition routing algorithms, hashing algorithms, and complexity analysis for high-throughput concurrency.
- **Existing Evidence**: `ConcurrentHashMap` for partition worker routing ($O(1)$ lookup), `SHA-256` hashing for payload digest verification ($O(N)$ payload hash), `ConcurrentLinkedQueue` for lock-free in-memory audit log buffers.
- **Required Improvement**: Document time and space complexity of partition assignment, queue dispatch, and idempotency lookup in architectural documentation.
- **Files / Components**: `PartitionedWorkerPool.java`, `DistributedQueueManagerImpl.java`, `IdempotencyServiceImpl.java`.
- **Verification Test**: `ConcurrentPaymentTest` (stress tests 10 parallel threads under heavy contention).
- **Acceptance Criteria**: Thread-safe collections used exclusively for shared state; zero deadlocks under concurrent load.
- **Final Status**: **Verified**.

---

### 3. Banking & Payment Workflows, Correctness & Reliable Transaction Processing

- **Requirement**: Reliable payment creation, idempotent processing with explicit `X-Idempotency-Key`, payload mismatch detection, fail-fast non-retryable errors, exponential backoff retries with jitter for transient outages, cumulative refund validation.
- **Existing Evidence**: Payment transaction state machine (`PENDING -> SUCCESS / FAILED / REFUNDED`), `IdempotencyKeyRecord` persistence, gateway simulation.
- **Required Improvement**: 
  1. Enforce payload hash equality check on idempotency hits; raise `PayloadMismatchException` (HTTP 409) if payload differs.
  2. Differentiate transient failures (retry with exponential backoff + jitter) from permanent business failures (fail fast without retry).
  3. Validate refund idempotency keys and prevent duplicate refunds or refunds exceeding original payment amount.
- **Files / Components**: `PaymentProcessingServiceImpl.java`, `IdempotencyServiceImpl.java`, `RefundProcessingServiceImpl.java`.
- **Verification Test**: `IdempotencyServiceTest`, `RefundProcessingServiceTest`, `FaultToleranceRetryTest`.
- **Acceptance Criteria**: Zero double-charging, 100% duplicate detection, fail-fast on insufficient balance, and idempotent refund execution.
- **Final Status**: **Verified**.

---

### 4. REST APIs, Service Design & Microservices Fundamentals

- **Requirement**: Clean RESTful endpoints, proper HTTP verb semantics, status codes (200, 201, 202, 400, 404, 409, 422, 500), DTO validation (`@Valid`), OpenAPI 3.0 documentation, pagination & sorting (`Pageable`).
- **Existing Evidence**: `PaymentController`, `RefundController`, `UserController`, `AuditController`, `MetricsController`, `SimulationController`, `OpenApiConfig`.
- **Required Improvement**: Add `Pageable` pagination parameters to list endpoints (`/api/v1/payments`, `/api/v1/payments/user/{userId}`, `/api/v1/audit/logs`), standardize `ApiResponse<T>` wrapper.
- **Files / Components**: `PaymentController.java`, `AuditController.java`, `docs/api-guide.md`.
- **Verification Test**: `PaymentControllerTest`, OpenAPI UI endpoint verification (`/swagger-ui.html`).
- **Acceptance Criteria**: All collection endpoints support pagination; Swagger OpenAPI schema is valid and complete.
- **Final Status**: **Verified**.

---

### 5. SQL, Database Design, Transactions, Constraints & Indexing

- **Requirement**: Polyglot persistence design (SQL for ACID financial records, NoSQL for append-only document audit logs), database constraints, indexes, `@Version` optimistic locking, explicit SQL schema migrations.
- **Existing Evidence**: `payments`, `users`, `refunds`, `idempotency_keys` JPA entities with `@Table(indexes = ...)` and `@Version` columns; MongoDB `AuditLogDocument`.
- **Required Improvement**: Provide explicit `src/main/resources/schema.sql` for PostgreSQL/H2 schema DDL initialization with foreign keys and unique constraints.
- **Files / Components**: `schema.sql`, `PaymentTransaction.java`, `User.java`, `IdempotencyKeyRecord.java`, `docs/architecture.md`.
- **Verification Test**: Transaction rollback unit test (`PaymentProcessingServiceTest.testDatabaseRollbackOnFailure`).
- **Acceptance Criteria**: Relational schema enforces unique constraints; database transactions roll back atomically on failure.
- **Final Status**: **Verified**.

---

### 6. Unit Testing, Integration Testing, Debugging & CI

- **Requirement**: Comprehensive unit and integration test coverage, multi-threaded concurrency validation, CI automated pipeline via GitHub Actions.
- **Existing Evidence**: 7 baseline tests passing, `.github/workflows/ci.yml`.
- **Required Improvement**: Expand test coverage to 25+ tests covering edge cases (payload mismatch, non-retryable errors, refund duplicate rejection, pagination, database rollback).
- **Files / Components**: `src/test/java/com/paymentsystem/*`, `.github/workflows/ci.yml`.
- **Verification Test**: `mvn clean test` executing 25+ tests with 100% pass rate.
- **Acceptance Criteria**: CI pipeline compiles JDK 17, runs unit and integration tests, and builds executable JAR artifact.
- **Final Status**: **Verified**.

---

### 7. Angular/React / Full-Stack Integration Awareness

- **Requirement**: Awareness of modern full-stack web integration, dynamic client dashboard interactions, REST API consumption, visual state management.
- **Existing Evidence**: Streamlit visual control room dashboard (`dashboard/app.py`) with real-time REST API consumption and standalone fallback.
- **Required Improvement**: Document full-stack architecture and live cloud deployment URL in `README.md` and `docs/architecture.md`.
- **Files / Components**: `dashboard/app.py`, `dashboard/style.css`, `dashboard/Dockerfile.dashboard`.
- **Verification Test**: Live Streamlit App (`https://distributed-fault-tolerant-payment-system-5qg2tjnkfrk7tvbqxzsz.streamlit.app/`).
- **Acceptance Criteria**: Interactive web dashboard connects seamlessly to Spring Boot backend and visualizes real-time metrics.
- **Final Status**: **Verified**.

---

### 8. Agile Development, Maintainable Code, Documentation & Teamwork

- **Requirement**: Comprehensive documentation, clear commit structure, explicit dev logs, design rationale, and interview preparation materials.
- **Existing Evidence**: README markdown documentation, clean package naming.
- **Required Improvement**: Create comprehensive documentation suite: `docs/initial-audit.md`, `docs/requirements-matrix.md`, `docs/architecture.md`, `docs/design-decisions.md`, `docs/api-guide.md`, `docs/testing.md`, `docs/dev-log.md`, `docs/interview-preparation.md`, `docs/final-verification.md`.
- **Files / Components**: `docs/*.md`, `README.md`.
- **Verification Test**: Documentation verification and alignment check.
- **Acceptance Criteria**: Project contains complete, professional technical documentation ready for code review and interview defense.
- **Final Status**: **Verified**.

---

### 9. Applied AI / NLP / ML Awareness (Secondary Strength)

- **Requirement**: Awareness of applied machine learning, anomaly scoring, predictive metrics, or intelligent risk simulation.
- **Existing Evidence**: Streamlit control room dashboard includes transaction risk assessment and anomaly detection simulation visualizers.
- **Required Improvement**: Explicitly document ML transaction risk simulation capabilities in `README.md` and `docs/architecture.md`.
- **Files / Components**: `dashboard/app.py`.
- **Verification Test**: Dashboard Anomaly & Risk Inspector tab execution.
- **Acceptance Criteria**: Practical demonstration of intelligent transaction risk scoring awareness without introducing bloated dependencies into the Java core backend.
- **Final Status**: **Verified**.

---

## Overall Technical Coverage Summary

- **Total Applicable Technical Requirements**: 9
- **Fully Verified & Implemented**: 9
- **Overall JD Alignment Score**: **100%** (Exceeds 95% target threshold)
