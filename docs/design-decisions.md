# Architectural Decision Records (ADRs)

**System**: Distributed Fault-Tolerant Payment System  
**Standard Chartered Bank — Development Engineer (Band 8)**  
**Date**: October 10, 2026  

---

## ADR 001: SHA-256 Cryptographic Request Hashing for Idempotency Conflict Detection

### Context
In distributed banking environments, clients may accidentally or maliciously reuse an existing idempotency key with a modified transaction payload (e.g., changing the amount or recipient). Storing raw full JSON bodies in indexed database columns introduces severe storage bloat and index key length violations in relational databases.

### Decision
Generate a 64-character hexadecimal SHA-256 digest of the normalized incoming JSON payload and store it in `idempotency_keys.request_hash`. On subsequent requests with the same key, compute the incoming hash and verify equality. If the hashes differ, throw `PayloadMismatchException` (HTTP 409 Conflict).

### Consequences
- **Positive**: Fixed 64-byte column footprint; high-performance cryptographic comparison; 100% prevention of idempotency key hijacking.
- **Negative**: Microsecond CPU overhead for hash computation (negligible compared to database network latency).

---

## ADR 002: Dual Error Taxonomy (Retryable vs. Non-Retryable Exceptions)

### Context
Baseline implementations often catch generic `Exception` and apply exponential backoff. In financial applications, retrying a payment that failed due to `Insufficient user balance` or `Validation error` wastes thread pool resources, introduces latency spikes, and will never succeed without an external deposit.

### Decision
Establish an explicit exception hierarchy rooted in `PaymentProcessingException`:
1. `NonRetryableException` (`InsufficientBalanceException`, `PayloadMismatchException`, `InvalidTransactionStateException`): Fail fast immediately on Attempt 1.
2. `RetryableException` (`GatewayTimeoutException`, `ServiceUnavailableException`, `LockAcquisitionException`): Eligible for exponential backoff retries with randomized jitter.

### Consequences
- **Positive**: Eliminates wasted retries on permanent business rejections; protects downstream payment networks from retry storms; preserves thread capacity.
- **Negative**: Requires strict discipline when classifying new domain exceptions.

---

## ADR 003: Account-Partitioned Asynchronous Queue Routing

### Context
Global shared queues (like a single Kafka topic or RabbitMQ queue with multiple concurrent consumers) can lead to race conditions where two payments for the *same* user are processed out of order or concurrently, leading to double-spending or locking contention on the user's account row.

### Decision
Implement consistent hashing partitioning in `PartitionedWorkerPool` where each transaction is dispatched to:
$$\text{Partition Index} = |\text{userId.hashCode}()| \pmod{\text{Partition Count}}$$
Each partition is consumed by a dedicated single worker thread.

### Consequences
- **Positive**: Guarantees strict FIFO execution per user account; eliminates thread contention on identical user records; enables horizontal multi-threaded throughput.
- **Negative**: Uneven distribution of user activity could cause partition skew (mitigated by increasing partition count).

---

## ADR 004: Relational ACID Storage (PostgreSQL) vs. Document Event Streaming (MongoDB)

### Context
A payment engine requires absolute ACID guarantees for account ledgers and transaction state transitions, but also needs high-throughput, audit logs that can store variable forensic metadata without requiring schema migrations.

### Decision
Adopt polyglot persistence:
- **PostgreSQL / H2**: Source of truth for accounts (`users`), transactions (`payments`), refunds (`refunds`), and idempotency locks (`idempotency_keys`).
- **MongoDB**: Append-only document store for historical compliance audit records (`AuditLogDocument`) with flexible metadata fields.

### Consequences
- **Positive**: Optimal performance profile for each operational requirement; zero schema migration friction for audit metadata; strict relational consistency for money balances.
- **Negative**: Requires managing two database connection pools in production.

---

## ADR 005: Cumulative Refund Limit Tracking

### Context
A payment of $100 can be refunded in multiple partial installments (e.g. $40 and then $30). Standard implementations that only check `request.amount <= payment.amount` fail to track cumulative refunds, allowing multiple refunds that exceed the original transaction value.

### Decision
In `RefundProcessingServiceImpl`, query all existing refunds for the given `paymentId` and verify:
$$\sum \text{Existing Refunds} + \text{New Refund Amount} \le \text{Original Payment Amount}$$
If the total reaches the payment amount, transition the payment status to `REFUNDED`.

### Consequences
- **Positive**: 100% mathematical protection against over-refunding; supports arbitrary partial refund sequences.
- **Negative**: Requires one additional indexed query against the `refunds` table per refund request.
