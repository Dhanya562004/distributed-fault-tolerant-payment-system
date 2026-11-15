# Distributed Fault-Tolerant Payment System

[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396.svg?style=flat-square&logo=openjdk)](https://www.oracle.com/java/)
[![Spring Boot 3.3.4](https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F.svg?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Tests](https://img.shields.io/badge/Tests-129%20Passing%20(H2)-10B981.svg?style=flat-square)](docs/testing.md)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![AI Usage](https://img.shields.io/badge/AI%20Usage-Disclosure-8B5CF6.svg?style=flat-square)](AI_USAGE.md)

A fault-tolerant payment processing engine built with Java 17 and Spring Boot 3.3. It focuses on transaction correctness, single-execution idempotency, failure classification, transactional outbox delivery, and database concurrency controls.

---

## What It Does

- **Cryptographic Idempotency Guard**: Evaluates incoming `X-Idempotency-Key` headers using SHA-256 payload hashing and database unique constraints. Duplicate requests within a 24-hour retention window return cached responses with an `Idempotent-Replayed: true` HTTP response header. Expired keys are atomically re-acquired without double-charging. A recovery process detects `IN_PROGRESS` keys stuck beyond a configurable threshold (default 120s), inspects payment status to prevent double-charging, and safely resolves them to `COMPLETED` (with cached response) or `FAILED` via an atomic conditional update.
- **Concurrency & Account Locking**: Protects user balances under concurrent load using pessimistic locking (`SELECT FOR UPDATE`) and JPA `@Version` optimistic locking. In-memory hash partitioning (`Math.floorMod(userId.hashCode(), N)`) routes async requests across internal worker threads by account ID for per-user FIFO execution within a single instance.
- **Failure Taxonomy & Full Jitter Retry Policies**: Differentiates between non-retryable business failures (e.g., insufficient funds, payload mismatches) which fail immediately, and retryable infrastructure failures (e.g., downstream socket timeouts, HTTP 503) which retry with exponential backoff and real Full Jitter (uniform random delay between 0 and the capped backoff). Repeated failures route to a Dead Letter Queue (DLQ).
- **Transactional Outbox & Webhook Engine**: Commits domain events atomically within the payment transaction, then asynchronously dispatches signed webhook payloads (HMAC-SHA256) with lease-based crash recovery and retry management. Webhook payloads include a timestamp (`timestamp.payload`) and requests older than 5 minutes (300 seconds) are rejected to prevent replay attacks, with full test coverage.
- **Cumulative Refund Validation**: Validates partial and full refunds against the original payment total, rejecting any attempt that exceeds the captured amount.
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
Runs the 129 unit and integration tests (controllers, service logic, outbox workers, idempotency expiry, stuck-key recovery, queue partitioning, and pagination).
- **Status**: 129 passed, 0 failures, 0 errors.

### 2. PostgreSQL 16 Testcontainers Suite (Requires Docker)
```bash
mvn test -Ppostgres-it
```
Runs 35 database-level concurrency tests against a real PostgreSQL 16 container, verifying row-level locking (`SELECT FOR UPDATE`), optimistic locking (`@Version`), MVCC transaction isolation, and outbox event claiming.
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
- [AI Usage Disclosure](AI_USAGE.md) — Full disclosure of AI assistance, human governance, and testing realities.

---

## License

Distributed under the [Apache 2.0 License](LICENSE).
