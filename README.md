# Distributed Fault-Tolerant Payment System

[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396.svg?style=flat-square&logo=openjdk)](https://www.oracle.com/java/)
[![Spring Boot 3.3.4](https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F.svg?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Tests](https://img.shields.io/badge/Tests-191%20Passing%20(H2)-10B981.svg?style=flat-square)](docs/testing.md)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![AI Usage](https://img.shields.io/badge/AI%20Usage-Disclosure-8B5CF6.svg?style=flat-square)](AI_USAGE.md)

A fault-tolerant payment processing engine built with Java 17 and Spring Boot 3.3. It focuses on transaction correctness, single-execution idempotency, failure classification, transactional outbox delivery, and database concurrency controls.

---

## What It Does

- **Immutable Double-Entry Ledger & SQL Reconciliation Engine**: Implements a double-entry financial ledger where every payment and refund writes matching `DEBIT` and `CREDIT` entries in the **exact same database transaction** as the balance change. Enforced append-only with Hibernate `@Immutable`, lifecycle callbacks (`@PreUpdate`, `@PreRemove` throwing `UnsupportedOperationException`), and a database unique constraint `(transaction_id, entry_side)` preventing duplicate retry entries. Features a high-performance reconciliation engine that executes pure SQL `SUM()` aggregations across snapshots/cutoffs—verifying that total debits equal total credits **per currency** and each user's wallet balance strictly matches the sum of their ledger rows (`credits - debits`). Mismatches and audit timestamps are logged and exposed live in `/api/v1/metrics`. Verified with tests covering payments, refunds, partial refunds, mid-transaction rollback atomicity, append-only protections, and deliberate row tampering.
- **Strict 5-State Finite State Machine & Conditional Locking**: Restructures payment state into 5 strict states (`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, `REFUNDED`) with exactly 5 permissible transitions (`PENDING -> PROCESSING`, `PENDING -> FAILED`, `PROCESSING -> SUCCESS`, `PROCESSING -> FAILED`, `SUCCESS -> REFUNDED`). Illegal transitions throw `InvalidTransactionStateException` via `PaymentTransaction.transitionTo()`, and direct `setStatus()` has been permanently removed. A partial refund leaves the payment in `SUCCESS` (updating `refundedAmount` and `remainingRefundable`), and transitions to `REFUNDED` only when the cumulative refund reaches the full payment amount. Tested with a hardcoded 25-pair (5x5) truth table in `PaymentStatusTransitionTest`.
- **Atomic Payment Commit & Transaction Boundary Correctness**: Guarantees that user balance deduction, payment status transition (`SUCCESS`), and transactional outbox event creation commit in **one single atomic database transaction** (`PaymentTransactionCoordinator.executePaymentAttempt`). This eliminates the critical failure mode where a server crash after balance deduction leaves money deducted with no corresponding success record or event. Reloads rows under pessimistic write lock (`SELECT FOR UPDATE`), isolates `PROCESSING` status commits into their own short transaction to prevent pool starvation and version conflicts, and ensures idempotent replays never double-charge. Verified in `AtomicPaymentCommitIntegrationTest`.
- **API Key Authentication & Tenant Scoping**: Enforces API key authentication across all REST endpoints (via `X-Api-Key` or `Authorization: Bearer <key>`) against the `api_keys` table. Health probes (`/actuator/health`) and OpenAPI docs remain public. Idempotency keys are strictly scoped per API key `(api_key, key_value)`, allowing multi-tenant customers to safely reuse identical idempotency keys (e.g. `ORDER_100`) without collisions or cross-customer data leakage.
- **End-to-End Request ID Tracing**: Attaches a unique `X-Request-Id` to every request (echoed in response headers), embedded into every SLF4J MDC log line (`[%X{requestId}]`), stored in `payments` and `outbox_events` tables, and propagated through background partition workers and outgoing webhook headers.
- **Standardized Error Envelope & Uniform Keyset/List Pagination**: Consistent `{ "code": "...", "message": "...", "requestId": "..." }` error envelopes across all handlers. All list endpoints (`/api/v1/payments`, `/api/v1/payments/user/{userId}`, `/api/v1/payments/transactions`, `/api/v1/users`, `/api/v1/refunds/payment/{paymentId}`, `/api/v1/audit/logs`) return a standardized `{ "data": [...], "pagination": { "limit": ..., "hasMore": ..., "nextCursor": ... } }` response envelope.
- **Cryptographic Idempotency Guard**: Evaluates incoming `X-Idempotency-Key` headers using SHA-256 payload hashing and database unique constraints. Duplicate requests within a 24-hour retention window return cached responses with an `Idempotent-Replayed: true` HTTP response header. Expired keys are atomically re-acquired without double-charging. A recovery process detects `IN_PROGRESS` keys stuck beyond a configurable threshold (default 120s), inspects payment status to prevent double-charging, and safely resolves them to `COMPLETED` (with cached response) or `FAILED` via an atomic conditional update.
- **Concurrency & Account Locking**: Protects user balances under concurrent load using pessimistic locking (`SELECT FOR UPDATE`) and JPA `@Version` optimistic locking. In-memory hash partitioning (`Math.floorMod(userId.hashCode(), N)`) routes async requests across internal worker threads by account ID for per-user FIFO execution within a single instance.
- **Failure Taxonomy & Full Jitter Retry Policies**: Differentiates between non-retryable business failures (e.g., insufficient funds, payload mismatches) which fail immediately, and retryable infrastructure failures (e.g., downstream socket timeouts, HTTP 503) which retry with exponential backoff and real Full Jitter (uniform random delay between 0 and the capped backoff). Repeated failures route to a Dead Letter Queue (DLQ).
- **Transactional Outbox & Webhook Engine**: Commits domain events atomically within the payment transaction, then asynchronously dispatches signed webhook payloads (HMAC-SHA256) with lease-based crash recovery and retry management. Webhook payloads include a timestamp (`timestamp.payload`) and requests older than 5 minutes (300 seconds) are rejected to prevent replay attacks, with full test coverage.
- **Cumulative Refund Validation**: Validates partial and full refunds against the original payment total, rejecting any attempt that exceeds the captured amount.
- **Whole Minor Units Money Representation**: Money is strictly modeled as integer amounts in the smallest currency unit (e.g., cents/paise) using `BIGINT` (Java `Long`), eliminating all floating-point rounding errors. A `currencies` database table tracks ISO-4217 currency codes and their decimal precisions (`USD`, `EUR`, `GBP`, `INR` = 2).
- **Keyset Pagination**: Implements deterministic cursor-based pagination (`createdAt DESC, id DESC`) with HMAC-SHA256 cursor integrity verification, avoiding SQL `OFFSET` performance penalties.

---

## Live Demo & Endpoints

| Service | URL | Notes |
| :--- | :--- | :--- |
| Streamlit Dashboard | [Live Dashboard](https://distributed-fault-tolerant-payment-system-5qg2tjnkfrk7tvbqxzsz.streamlit.app/) | Interactive interface for transactions, metrics, and fault simulation |
| Backend API | [Render API](https://apexpay-backend-sc5s.onrender.com) | Spring Boot backend (Render free tier; spins down after 15 min of inactivity) |
| Health Check | [`/actuator/health`](https://apexpay-backend-sc5s.onrender.com/actuator/health) | Reports application and database health status |
| Metrics Endpoint | [`/api/v1/metrics`](https://apexpay-backend-sc5s.onrender.com/api/v1/metrics) | Live counters for transactions, retries, idempotency hits, and latency |
| Swagger OpenAPI | [`/swagger-ui.html`](https://apexpay-backend-sc5s.onrender.com/swagger-ui.html) | Interactive OpenAPI 3.0 documentation |

---

## How to Run It

### Prerequisites
- Java 17+
- Apache Maven 3.9+
- Python 3.10+ (only required if running the Streamlit dashboard locally)
- Docker & Docker Compose (optional, for multi-container deployment)

### 1. Run the Backend Locally
```bash
# Clone the repository
git clone https://github.com/Dhanya562004/distributed-fault-tolerant-payment-system.git
cd distributed-fault-tolerant-payment-system

# Start backend (uses embedded H2 in PostgreSQL compatibility mode)
mvn spring-boot:run
```
The server will start at `http://localhost:8080`. Verify with `curl http://localhost:8080/actuator/health`.

### 2. Run the Dashboard Locally (Optional)
```bash
cd dashboard
pip install -r requirements.txt
streamlit run app.py
```
Open `http://localhost:8501` and set the backend URL to `http://localhost:8080` in the sidebar.

### 3. Run with Docker Compose
```bash
docker-compose up --build
```
Provisions the backend (`8080`), PostgreSQL (`5432`), MongoDB (`27017`), and Streamlit dashboard (`8501`).

---

## How to Test It

### 1. Fast H2 Test Suite (No Docker Required)
```bash
mvn clean test
```
Runs the 191 unit and integration tests (double-entry ledger debit/credit matching, SQL reconciliation audits, append-only immutability, finite state machine transitions, cumulative refund limit checks, atomic payment commit, security filters, API key auth, scoped idempotency, request ID propagation, standardized errors, controllers, service logic, outbox workers, idempotency expiry, stuck-key recovery, queue partitioning, currency service, and pagination).
- **Status**: 191 passed, 0 failures, 0 errors.

### 2. PostgreSQL 16 Testcontainers Suite (Requires Docker)
```bash
mvn test -Ppostgres-it
```
Runs 38 database-level concurrency tests against a real PostgreSQL 16 container, verifying row-level locking (`SELECT FOR UPDATE`), optimistic locking (`@Version`), MVCC transaction isolation, outbox event claiming, and stuck-key recovery.
- **Status**: Passes in environments where Docker is running (e.g. GitHub Actions CI). In environments without Docker running, this command fails during container startup.

### 3. In-Process Concurrency Benchmark
```bash
mvn test -Dtest=ConcurrentPaymentTest
```
Spawns 50 threads firing 1,000 duplicate requests with the same idempotency key and payload against the payment service to verify single execution and zero double-spending.

Results observed from running `mvn test -Dtest=ConcurrentPaymentTest` (service-level, in-process H2):
| Run | Requests | Workers | Throughput | Total Time | Avg Latency | p50 | p95 | p99 | Original Charges | Cached / 409 Conflicts | Failures |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| #1 | 1,000 | 50 | 484.5 req/s | 2,063.9 ms | 93.68 ms | 6.97 ms | 635.63 ms | 1,368.77 ms | 1 | 999 | 0 |
| #2 | 1,000 | 50 | 920.5 req/s | 1,086.4 ms | 46.73 ms | 5.28 ms | 250.02 ms | 546.82 ms | 1 | 999 | 0 |
| #3 | 1,000 | 50 | 1,241.4 req/s | 805.5 ms | 34.54 ms | 3.79 ms | 188.33 ms | 519.68 ms | 1 | 999 | 0 |

In each run, the user's balance was deducted exactly once (from $1,000.00 to $900.00), exactly one transaction record was persisted, and all 999 concurrent duplicate requests were either served from cache or rejected with 409 Conflict.

### 4. Test Coverage
Measured with JaCoCo (`mvn test jacoco:report`):
- **Line Coverage**: 69.1% (1,759 / 2,546 lines covered)
- **Branch Coverage**: 61.5% (380 / 618 branches covered)

---

## Safe Production Changes & Rollback Runbook

### 1. Zero-Downtime Database Migrations (Flyway)
The relational database schema is version-controlled and applied at startup via **Flyway** (`src/main/resources/db/migration/`):
- `V1__initial_schema.sql`: Authoritative baseline DDL defining all 8 core tables (`currencies`, `api_keys`, `users`, `payments`, `idempotency_keys`, `refunds`, `outbox_events`, `ledger_entries`), primary keys, unique constraints, and high-performance b-tree indexes.
- `V2__seed_initial_data.sql`: Idempotent baseline seed data configuring ISO-4217 currencies (`USD`, `EUR`, `GBP`, `INR`), test users (`USR_ALICE`, `USR_BOB`, `USR_CHARLIE`, `USR_DAVID`), and default admin API keys.
- **Hibernate DDL Validation**: `spring.jpa.hibernate.ddl-auto=validate` is strictly enforced across dev, test, and production profiles. Hibernate validates entity mappings against Flyway-managed tables on application startup and refuses to boot on schema drift.

### 2. Two-Step Expand & Contract Safe Changes
All schema modifications follow the **Expand / Contract** pattern to allow zero-downtime rolling deployments without breaking active application instances.
- **Detailed Runbook**: A complete, step-by-step production example detailing adding a `fee_amount` column, backward-compatible dual-writing code, chunked online batch backfills using `FOR UPDATE SKIP LOCKED`, and final constraint contraction is documented in [docs/safe-changes.md](docs/safe-changes.md).

### 3. Ledger Feature Flag & Zero-Downtime Rollback
The double-entry financial ledger and reconciliation audit engine are guarded by the operational feature flag:
```yaml
app:
  ledger:
    enabled: ${LEDGER_ENABLED:true}
```
- **Live Observability**: The current status of the feature flag and reconciliation engine is published live at `/api/v1/metrics`:
  ```json
  {
    "ledgerEnabled": true,
    "reconciliationStatus": "BALANCED",
    "reconciliationMismatchCount": 0,
    "lastReconciliationTime": "2026-11-16T12:00:00Z"
  }
  ```

#### Immediate Rollback Procedure
If a production incident, database performance bottleneck, or unforeseen ledger deadlock occurs:
1. **Disable Ledger Engine via Environment Variable**:
   Set `LEDGER_ENABLED=false` in the production environment (e.g., Kubernetes Deployment ConfigMap, AWS ECS Task Definition, or Render Environment Settings):
   ```bash
   export LEDGER_ENABLED=false
   ```
2. **Execute Rolling Application Restart**:
   Restart worker nodes without dropping or altering database tables:
   ```bash
   kubectl rollout restart deployment/apexpay-backend
   ```
3. **Operational Impact During Rollback**:
   - **Payment & Refund Processing**: Continue processing payments and issuing refunds without downtime. Account balances are safely deducted and credited under existing atomic transaction controls.
   - **Ledger Entries**: Calls to `recordPaymentEntries()`, `recordRefundEntries()`, and `recordOpeningBalance()` become immediate no-ops. Zero writes are made to `ledger_entries`.
   - **Reconciliation Engine**: Background reconciliation jobs skip audit queries and immediately report status `"DISABLED"`.
   - **Zero Data Loss & Zero Schema Reversion**: Existing ledger rows remain preserved and intact. No `DROP TABLE` or `ALTER TABLE` is required.
4. **Post-Incident Recovery**:
   Once root cause is remediated, set `LEDGER_ENABLED=true`, restart instances, and execute the startup backfill runner to re-establish matching ledger entries for transactions processed during the maintenance window.

---

## Known Limits

- **Demo Persistence**: The public Render deployment uses an in-memory H2 database (`MODE=PostgreSQL`). Data does not persist across service restarts or free-tier sleep cycles. A PostgreSQL configuration is provided in `application-prod.yml`.
- **In-Memory Partition Queues**: Worker queues are held in memory (`LinkedBlockingQueue`) inside a single JVM instance. If the application crashes, any payments waiting in the in-memory queue are lost (they remain in `PROCESSING` state in the database, with their idempotency key in `IN_PROGRESS` until the stuck-key recovery job marks them `FAILED`). Furthermore, per-user FIFO ordering is preserved only within a single application instance; multiple instances would race independently unless partitioned at an external message broker or database lock level.
- **Synthetic Banking Rails**: Payment authorization calls are handled by an internal simulator (`GatewaySimulationServiceImpl`) with configurable latency and error rates, not live banking networks.
- **Educational Scope**: This project is an architectural reference and demonstration. It is not PCI-DSS certified and should not be used to process real monetary transactions.
- **Docker Dependency for Testcontainers**: The PostgreSQL integration tests require a running Docker daemon and cannot execute in environments without Docker.
- **Benchmark Scope**: The 1,000-request concurrency benchmark runs in-process at the service layer and does not measure HTTP server serialization or network latency.

---

## Documentation

- [System Architecture](docs/architecture.md) — Topology, concurrency model, partitioned queues, and retry mechanisms.
- [API Guide](docs/api-guide.md) — Endpoint specifications, request/response schemas, and example payloads.
- [Test Strategy](docs/testing.md) — Test pyramid, locking verification, and benchmark methodology.
- [Architectural Decision Records (ADRs)](docs/design-decisions.md) — Rationale for cryptographic hashing, failure taxonomy, and persistence choices.
- [Safe Production Changes Runbook](docs/safe-changes.md) — Two-step zero-downtime database changes, Flyway migrations, feature flags, and rollback runbooks.
- [AI Usage Disclosure](AI_USAGE.md) — Full disclosure of AI assistance, human governance, and testing realities.

---

## License

Distributed under the [Apache 2.0 License](LICENSE).
