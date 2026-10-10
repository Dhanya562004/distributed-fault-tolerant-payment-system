# Standard Chartered Bank — Technical Interview Preparation Guide

**Role**: Development Engineer (Band 8)  
**Domain**: Distributed Banking & Payment Systems  
**Project**: Distributed Fault-Tolerant Payment System  
**Verified Basis**: 100% verified against current codebase  

---

### 1. Explain the architecture in two minutes.
> "The system is an enterprise distributed payment processing engine built with Java 17 and Spring Boot 3.3. It separates payment ingestion into synchronous direct processing and asynchronous worker pool processing.
>
> In the ingestion boundary, incoming requests pass through an atomic Idempotency Engine backed by PostgreSQL with SHA-256 payload digest verification to guarantee single-execution semantics.
>
> For synchronous payments, transactions are executed under strict `@Transactional(isolation = Isolation.READ_COMMITTED)` boundaries with an intelligent failure taxonomy: transient gateway outages are retried using exponential backoff with full jitter, whereas permanent business failures (such as insufficient balance) fail fast on Attempt 1.
>
> For asynchronous payments, requests are dispatched into an in-memory partitioned queue using consistent hashing on `userId`. Each partition is consumed sequentially by a dedicated worker thread to preserve strict per-user FIFO ordering while scaling throughput horizontally across multiple accounts.
>
> Finally, we use polyglot persistence: PostgreSQL enforces relational integrity and balances, while MongoDB captures append-only, immutable audit state transitions."

---

### 2. How does idempotency prevent duplicate payment creation?
> "When a client sends `X-Idempotency-Key`, we compute a 64-character SHA-256 hash of the JSON request body. We then attempt to insert an `IN_PROGRESS` record into the `idempotency_keys` table, which has a database-level `UNIQUE` constraint on `key_value`.
>
> - If two identical requests hit the server concurrently, the database unique constraint raises a `DataIntegrityViolationException`, caught and returned as a `409 Conflict`.
> - If a request arrives with a key that is already `COMPLETED`, we verify that the stored `request_hash` matches the incoming hash. If they match, we immediately return the cached response with `"isCachedIdempotentResponse": true`.
> - If the same key is reused with a different payload (e.g. amount modified from $100 to $500), our engine detects the hash discrepancy and rejects the request with `PayloadMismatchException` (HTTP 409 Conflict)."

---

### 3. What happens if a request times out after the database commits?
> "This is a classic dual-write distributed systems challenge. If the database commits the transaction and marks the idempotency key `COMPLETED`, but the network connection drops before the client receives the HTTP 200 response, the client's HTTP client will experience a timeout and retry.
>
> When the client retries with the same `X-Idempotency-Key` and payload, our `IdempotencyService` hits the database, identifies that the key is already `COMPLETED` with an identical payload hash, and replays the cached response without re-executing payment side effects or re-debiting the user's account."

---

### 4. How are concurrent requests handled?
> "Concurrency is managed at three distinct layers:
> 1. **Idempotency Locks**: Database unique constraints on `idempotency_keys(key_value)` serialize duplicate submissions for the same transaction key.
> 2. **Account Balances**: User balance updates utilize Spring JPA `@Version` optimistic locking to detect concurrent race conditions and rollback on conflicts.
> 3. **Partitioned Worker Queues**: In asynchronous processing, requests are routed via `Math.abs(userId.hashCode()) % partitionCount`. Because transactions for the same user land in the same partition, a single thread executes them sequentially, eliminating thread contention on identical user records."

---

### 5. What database transaction boundaries are used?
> "We define transactional boundaries using Spring's `@Transactional(isolation = Isolation.READ_COMMITTED)`. 
>
> Inside `executeSinglePaymentAttempt`:
> 1. The user account is retrieved.
> 2. The balance invariant is verified (`balance >= amount`).
> 3. The balance is decremented and saved.
> 4. The upstream gateway authorization is called.
> 
> If an unhandled exception or database error occurs during this execution, Spring marks the transaction for rollback, rolling back the user account balance modification."

---

### 6. What happens if an external dependency fails?
> "We categorize external dependency failures into our domain exception taxonomy:
> - **Transient Outages** (`GatewayTimeoutException`, `ServiceUnavailableException`, `LockAcquisitionException`): Caught by the retry engine and retried up to 3 times with exponential backoff and randomized jitter to prevent thundering herd problems.
> - **Permanent Upstream Declines / Business Errors** (`InsufficientBalanceException`, `PayloadMismatchException`): Fail fast immediately on Attempt 1.
> - If all 3 retry attempts are exhausted, the payment is transitioned to `FAILED`, the failure reason is recorded, and an audit event (`PAYMENT_REJECTED`) is emitted."

---

### 7. How do retries avoid duplicate side effects?
> "In our synchronous flow, retries occur *within* the initial client request lifecycle prior to committing the idempotency key. The payment record remains in status `PENDING` across retry attempts, and the idempotency record stays in status `IN_PROGRESS`. 
>
> When an attempt succeeds, the status transitions to `SUCCESS` and the idempotency record is marked `COMPLETED` atomically. In the asynchronous queue path, messages store the unique `paymentId`, and workers check whether the transaction has already reached a terminal state (`SUCCESS` or `FAILED`) before executing."

---

### 8. What is the purpose of each Java interface and service?
> - `PaymentProcessingService`: Core business orchestration for synchronous and asynchronous payment lifecycles and state transitions.
> - `IdempotencyService`: Contract for SHA-256 payload digest calculation, atomic key acquisition, and cached response storage.
> - `RefundProcessingService`: Validates refund eligibility, enforces cumulative refund ceilings, and manages account balance crediting.
> - `AuditService`: Polyglot audit logging contract recording state transitions into MongoDB with in-memory fallback.
> - `GatewaySimulationService`: Simulates external scheme latency, network timeouts, and 503 HTTP gateway declines.
> - `DistributedQueueManager`: Manages partitioned message distribution and Dead Letter Queue (DLQ) isolation."

---

### 9. Which data structures are used and why?
> - `ConcurrentHashMap`: Thread-safe partition queue routing and worker thread registry ($O(1)$ lookup time, lock striping).
> - `LinkedBlockingQueue`: Thread-safe FIFO message queues for each worker partition.
> - `ConcurrentLinkedQueue`: Lock-free, non-blocking in-memory circular buffer for fallback audit logs.
> - `MessageDigest` (SHA-256): Cryptographic hash generator producing 32-byte digests formatted as 64-character hexadecimal strings."

---

### 10. What is the time and space complexity of relevant operations?
> - **Idempotency Key Verification**: $O(K)$ time where $K$ is the payload size for SHA-256 hashing + $O(1)$ indexed B-tree lookup in PostgreSQL.
> - **Partition Queue Dispatch**: $O(1)$ time for hash code partition assignment and queue insertion.
> - **Cumulative Refund Check**: $O(R)$ time where $R$ is the number of prior refunds on the specific payment (typically $R \le 5$).
> - **Space Complexity**: $O(N)$ where $N$ is the active transaction volume."

---

### 11. Why PostgreSQL and MongoDB?
> - **PostgreSQL**: Selected for core financial transactional data (`users`, `payments`, `refunds`, `idempotency_keys`) where ACID properties, unique constraints, and schema enforcement are non-negotiable.
> - **MongoDB**: Selected for audit events (`transaction_audit_logs`). Audit records are append-only, high-volume event documents with variable metadata fields (e.g., latency, worker IDs, stack traces). MongoDB provides horizontal write scaling without schema migrations."

---

### 12. What tests verify correctness?
> "We maintain 20 automated tests:
> - `ConcurrentPaymentTest`: 10 parallel threads contending for the same idempotency key; verifies 1 success, 9 lock conflicts caught, and zero double-spending.
> - `FaultToleranceRetryTest`: Injects socket timeouts and verifies backoff recovery.
> - `IdempotencyServiceTest`: Tests new keys, in-progress locks, cached replays, and payload mismatch rejection.
> - `PaymentProcessingServiceTest`: Tests payment success, fail-fast on insufficient balance (0 retries), and pagination.
> - `RefundProcessingServiceTest`: Tests partial refunds, cumulative refund limits, and duplicate refund caching.
> - `PaymentLifecycleIntegrationTest`: End-to-end integration test spanning user creation, payments, conflicts, refunds, and audit logging."

---

### 13. What are the current limitations?
> 1. In-memory queue manager: The partition queue operates within a single JVM process; in an enterprise multi-node deployment, this would be replaced with Apache Kafka or AWS SQS.
> 2. Database transactions do not span external gateway calls (2-phase commit is avoided in favor of the Saga pattern)."

---

### 14. What would be required before production deployment?
> 1. External distributed message broker (Apache Kafka or AWS SQS) for partitioned worker queues across multiple cluster nodes.
> 2. Distributed caching layer (Redis Cluster) for sub-millisecond idempotency lock acquisition.
> 3. Production secret management via HashiCorp Vault or AWS Secrets Manager.
> 4. Mutual TLS (mTLS) for gateway scheme communication.
> 5. Flyway or Liquibase database migration automation integrated into CD pipelines."
