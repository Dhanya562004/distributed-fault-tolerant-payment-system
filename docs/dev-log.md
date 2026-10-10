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
