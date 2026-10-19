# Resume Project Entry & Technical Interview Guide

**Project**: ApexPay — Distributed Fault-Tolerant Payment Engine  
**Target Roles**: Backend Engineer / Payment Infrastructure Engineer (Stripe, Standard Chartered Bank, Adyen, PayPal)  
**Primary Tech Stack**: Java 17, Spring Boot 3.3, PostgreSQL 16, REST APIs, Docker, Testcontainers, JUnit 5, GitHub Actions, Streamlit Cloud, Render  

---

## 1. ApexPay — Live Project Links

All cloud endpoints and repositories are verified live and accessible:

| Component | Verified Live Link | Purpose |
| :--- | :--- | :--- |
| **GitHub Repository** | [https://github.com/Dhanya562004/distributed-fault-tolerant-payment-system](https://github.com/Dhanya562004/distributed-fault-tolerant-payment-system) | Source code, architecture documentation, test suites, and GitHub Actions CI/CD workflows. |
| **Live Streamlit Dashboard** | [https://distributed-fault-tolerant-payment-system-5qg2tjnkfrk7tvbqxzsz.streamlit.app/](https://distributed-fault-tolerant-payment-system-5qg2tjnkfrk7tvbqxzsz.streamlit.app/) | Interactive control room featuring KPI metrics, transaction inspection, and fault injection lab. |
| **Spring Boot Backend** | [https://apexpay-backend-sc5s.onrender.com](https://apexpay-backend-sc5s.onrender.com) ([Health: `/actuator/health`](https://apexpay-backend-sc5s.onrender.com/actuator/health)) | Deployed Spring Boot payment processing API (running on Render container environment). |
| **Swagger API Documentation** | [https://apexpay-backend-sc5s.onrender.com/swagger-ui.html](https://apexpay-backend-sc5s.onrender.com/swagger-ui.html) | Interactive documentation for implemented REST APIs. |
| **Live Telemetry** | [https://apexpay-backend-sc5s.onrender.com/api/v1/metrics](https://apexpay-backend-sc5s.onrender.com/api/v1/metrics) | Real-time application monitoring and operational metrics. |

---

## 2. Stripe-Focused Resume Project Entry

### ApexPay — Distributed Fault-Tolerant Payment Engine
*Core Technologies*: **Java 17 | Spring Boot 3.3 | PostgreSQL | REST APIs | Idempotency | Concurrency Control | Transactional Outbox | Webhook Reliability | HMAC-SHA256 | JUnit 5 | Testcontainers | GitHub Actions | Cloud Deployment (Render & Streamlit Cloud)**

- **Cryptographic Idempotency Engine**: Designed an atomic idempotency guard utilizing database-level unique constraints and SHA-256 payload digest verification; prevents duplicate payment creation across network retries, enforces 24-hour retention expiry with atomic re-acquisition, and transparently returns `Idempotent-Replayed: true` HTTP response headers.
- **Transactional Outbox & Reliable Webhooks**: Implemented the Transactional Outbox pattern to solve dual-write distributed inconsistencies, committing outbox events within the same database transaction as payment mutations; built a scheduled polling worker with lease locking, exponential backoff, dead-letter queue (DLQ) routing, and HMAC-SHA256 signature verification (`X-ApexPay-Signature`).
- **Concurrency Control & Account Isolation**: Enforced strict ACID consistency across concurrent account debits by combining JPA `@Version` optimistic locking, pessimistic row-level locking (`SELECT FOR UPDATE`), and account-partitioned worker queues using consistent hashing (`userId.hashCode() % N`) to guarantee per-account FIFO ordering without cross-account bottlenecks.
- **Deterministic Keyset Cursor Pagination**: Built an $O(\log N + K)$ keyset cursor pagination engine using composite indexed ordering (`created_at DESC, payment_id DESC`), HMAC-SHA256 cryptographic cursor tamper-proofing, and API key authorization to safely paginate high-volume transaction collections without table scan degradation.
- **Fail-Fast Error Taxonomy**: Built an intelligent failure classifier differentiating non-retryable business violations (fail-fast on Attempt 1 with zero retries for insufficient funds or payload conflicts) from transient infrastructure outages (exponential backoff with full jitter and DLQ failover).
- **Automated Testing & Cloud CI/CD**: Engineered an automated 153-test verification suite (118 fast H2 unit/integration tests and 35 PostgreSQL 16 Testcontainers integration tests) in a 3-stage GitHub Actions CI/CD pipeline, maintaining live deployment across Render and Streamlit Cloud.

---

## 3. Best Stripe-Focused Resume Bullets (Tailored Formats)

### Option A: Architectural & Reliability Focus (Recommended for Infrastructure Roles)
> "Architected **ApexPay**, a distributed fault-tolerant payment engine using **Java 17, Spring Boot 3.3, and PostgreSQL**; engineered an atomic **SHA-256 idempotency engine** with 24h retention and `Idempotent-Replayed` headers, a **Transactional Outbox pattern** with **HMAC-SHA256 reliable webhooks**, and a **153-test automated CI pipeline** with **PostgreSQL 16 Testcontainers** deployed live on **Render** and **Streamlit Cloud**."

### Option B: Payment Platform & Financial Invariants Focus
> "Developed a production-grade transaction processing engine modeled after Stripe’s core payment rails; implemented **cryptographic idempotency guards**, **JPA optimistic & pessimistic row-level locking**, and **deterministic keyset cursor pagination**; verified zero double-spending under concurrent stress tests and validated full outbox lifecycle via **automated GitHub Actions CI** and **live cloud endpoints**."

---

## 4. Technical Interview Questions (Stripe Core Infrastructure Focus)

### Q1: How does your idempotency layer differentiate between a network retry and an accidental duplicate request with different parameters?
**Answer**:
> "When a client sends `X-Idempotency-Key`, we compute a SHA-256 hash digest of the incoming JSON payload. In PostgreSQL, `idempotency_keys` enforces a `UNIQUE(key_value)` constraint.
> - If the key is new, an `IN_PROGRESS` record is atomically acquired.
> - If an identical key arrives and is already `COMPLETED`, we compare `stored_hash == incoming_hash`. If they match, we immediately return the cached response with the `Idempotent-Replayed: true` HTTP header, preventing duplicate debiting.
> - If the incoming hash differs from the stored hash (e.g., the caller reused a key but modified the payment amount), the engine immediately throws `PayloadMismatchException` and returns `409 Conflict`. We never allow key re-use with mutated payloads within the 24-hour validity window."

### Q2: Why use the Transactional Outbox Pattern instead of publishing directly to a message broker or webhook endpoint from the controller?
**Answer**:
> "Calling external HTTP endpoints or publishing to message brokers directly inside a database transaction creates the dual-write problem:
> 1. If the external network call succeeds but the local DB commit fails, an external webhook was fired for a payment that never persisted.
> 2. If the DB commits but the external call times out, the webhook event is lost forever.
> 
> The Transactional Outbox pattern guarantees atomicity by writing the payment record and the outbox event (`outbox_events`) within the exact same relational database transaction (`@Transactional`). A background `WebhookDeliveryWorker` then asynchronously polls pending outbox entries, acquires an atomic lease lock, computes an HMAC-SHA256 signature, and attempts delivery with exponential backoff and jitter. If an unrecoverable failure occurs after 5 attempts, it routes to a Dead Letter Queue (DLQ)."

### Q3: Why is keyset pagination preferred over offset/limit in financial transaction ledgers?
**Answer**:
> "Offset/limit pagination (`OFFSET 100000 LIMIT 20`) requires the database query engine to scan and discard 100,000 rows before returning the requested 20, resulting in $O(N)$ execution time and significant database I/O degradation. Furthermore, offset pagination suffers from page drift: if new payments are inserted while a user navigates pages, transactions shift across page boundaries and appear twice.
> 
> In contrast, our keyset cursor pagination utilizes composite indexing on `(created_at DESC, payment_id DESC)`. The cursor encodes the exact tuple values of the last seen record (`created_at` and `payment_id`), signed with HMAC-SHA256. The SQL query simply executes `WHERE (created_at < :cursorTime) OR (created_at = :cursorTime AND payment_id < :cursorId) ORDER BY created_at DESC, payment_id DESC LIMIT :limit`, which uses a B-tree index seek in $O(\log N + K)$ time with zero page drift."

### Q4: How do you verify database concurrency and locking in CI without depending on shared external databases?
**Answer**:
> "We separate our test suite into two stages:
> - **Stage A (Local Fast)**: 118 unit and integration tests using in-memory H2 with zero external dependencies, running in ~15 seconds.
> - **Stage B (PostgreSQL 16 Testcontainers)**: 35 tests running against a real PostgreSQL 16 Docker container managed by Testcontainers. This validates real MVCC, row-level locks (`SELECT FOR UPDATE`), JPA `@Version` optimistic locking conflicts, and a 1,000-request concurrent duplicate storm across 50 threads, proving zero double-spending under authentic PostgreSQL lock management."
