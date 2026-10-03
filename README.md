# ⚡ Distributed Fault-Tolerant Payment & Transaction Processing System

A production-grade, highly scalable **Distributed Fault-Tolerant Payment & Transaction Processing Engine** engineered in **Java 17 + Spring Boot 3.3**, designed for high-throughput distributed environments (similar to backend infrastructure at Stripe and PayPal).

---

## 🏛️ System Architecture & Design Overview

The system is built upon a **stateless API layer, partitioned async queue messaging, decoupled worker pools, atomic idempotency guards, and hybrid SQL + NoSQL database storage**.

```mermaid
flowchart TD
    Client[Client / Streamlit UI Dashboard] -->|REST API with X-Idempotency-Key| LoadBalancer[Stateless API Load Balancer Layer]
    
    subgraph Spring Boot Application Node
        LoadBalancer --> APIController[Payment / Refund REST Controllers]
        APIController --> IdempotencyGuard{Idempotency Engine}
        
        IdempotencyGuard -->|Duplicate Key & Completed| ReturnCached[Return Cached Response HTTP 200]
        IdempotencyGuard -->|Duplicate Key & In-Progress| ReturnConflict[Return HTTP 409 Conflict]
        IdempotencyGuard -->|New Key| LockKey[Acquire Atomic Lock in SQL DB]
        
        LockKey --> RouteDecision{Processing Mode}
        
        RouteDecision -->|Synchronous Direct| SyncEngine[Sync Payment Processing Engine]
        RouteDecision -->|Asynchronous Queue| PartitionRing[Partitioned Event Queue Manager]
        
        SyncEngine --> RetryLoop[Exponential Backoff + Jitter Retry Loop]
        RetryLoop --> GatewaySimulator[Payment Gateway Authorization]
        
        PartitionRing -->|Consistent Hashing on userId| Worker1[Partition Worker 0]
        PartitionRing -->|Consistent Hashing on userId| Worker2[Partition Worker 1]
        PartitionRing -->|Consistent Hashing on userId| Worker3[Partition Worker 2]
        
        Worker1 --> GatewaySimulator
        Worker2 --> GatewaySimulator
        Worker3 --> GatewaySimulator
        
        GatewaySimulator -->|Exhausted Retries| DLQ[Dead Letter Queue - DLQ]
    end

    subgraph Data & Persistence Layer
        LockKey <---> PostgreSQL[(SQL Database: Users, Payments, Idempotency Keys)]
        SyncEngine <---> PostgreSQL
        Worker1 <---> PostgreSQL
        
        APIController -.-> AuditService[NoSQL Document Audit Service]
        AuditService ---> MongoDB[(MongoDB / Document Store: Transaction Logs, Events)]
    end

    subgraph Observability
        APIController --> MetricsTracker[Micrometer / Prometheus Metrics Engine]
        MetricsTracker --> MetricsEndpoint[GET /api/v1/metrics]
    end
```

---

## ⚙️ Core Engineering Pillars

### 1. 🛡️ Absolute Idempotency Engine (`IdempotencyService`)
- Enforces strict single-execution guarantees across distributed retry storms.
- Uses MD5/SHA-256 request payload digests stored atomically in the SQL database (`idempotency_keys` table).
- Prevents double-charging by serving cached HTTP response payloads on identical retries and raising `409 Conflict` during concurrent processing windows.

### 2. 🔀 Partitioned Queue & Scalable Worker Pool (`DistributedQueueManager`)
- Implements consistent hashing partition routing based on `userId` / `partitionKey`.
- Ensures **strict FIFO ordering per user** while allowing horizontal scaling of worker threads across independent partitions.
- Dead Letter Queue (`DLQ`) isolates unrecoverable messages after max retry attempts.

### 3. 🔁 Fault Tolerance, Retries & Jitter (`GatewaySimulationService`)
- Implements exponential backoff (`backoff = initial * 2^attempt + jitter`) to mitigate downstream thundering herd problems.
- Built-in simulation lab tests network latency, socket timeouts, 503 gateway outages, and database connection lock conflicts.

### 4. 🔐 Concurrency Controls & Consistency
- Combined **Optimistic Locking (`@Version`)** and **Pessimistic Row Locking (`SELECT FOR UPDATE`)** on user balance modifications to prevent race conditions and double-spending under high concurrency.
- Atomic Spring `@Transactional(isolation = Isolation.READ_COMMITTED)` boundaries ensure ACID compliance.

### 5. 📜 Dual Relational (SQL) & Document (NoSQL) Persistence
- **SQL (PostgreSQL / H2)**: Stores structured transactions, user accounts, and idempotency lock records.
- **NoSQL (MongoDB / In-Memory Store)**: Records append-only, immutable document audit logs (`transaction_logs`) tracking state transitions (`PENDING -> PROCESSING -> SUCCESS / FAILED`).

---

## 🔌 REST API Specification

| HTTP Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/payments` | Initiate payment transaction with `X-Idempotency-Key` header |
| `GET` | `/api/v1/payments/{paymentId}` | Fetch status and transaction details |
| `GET` | `/api/v1/payments` | List all historical payment records |
| `POST` | `/api/v1/refunds` | Process refund and restore user account balance |
| `GET` | `/api/v1/refunds/{refundId}` | Fetch refund status |
| `GET` | `/api/v1/audit/logs` | Query NoSQL state change audit trail |
| `GET` | `/api/v1/metrics` | Real-time throughput, latency, retry counts, and DLQ depth |
| `POST` | `/api/v1/simulation/config` | Inject network latency, timeouts, or 503 gateway outages |
| `POST` | `/api/v1/simulation/reset` | Clear fault injection rules |

---

## 🌐 Streamlit Visual Monitoring Dashboard

A Streamlit visualization UI in Python consumes backend REST APIs in real-time.

```bash
# Run Streamlit Dashboard locally
cd dashboard
pip install -r requirements.txt
streamlit run app.py
```

### Dashboard Features:
- 📊 **Live Transactions Feed**: Real-time payment table with status badges (`SUCCESS`, `FAILED`, `PENDING`, `REFUNDED`).
- 💳 **Payment Processing Terminal**: Submit synchronous or queue-based asynchronous payments & refunds.
- 📈 **System Performance Analytics**: Plotly metrics charts, success rate gauges, and average latency graphs.
- 📜 **NoSQL Audit Inspector**: Timeline view of payment state transitions and worker thread signatures.
- 🛡️ **Fault Injection Lab**: Dynamically toggle latency, network timeouts, and 503 gateway outages.

---

## 🚀 Running the Project

### Prerequisites
- JDK 17+ (or JDK 21 / 24)
- Apache Maven 3.9+
- Docker & Docker Compose (Optional for container deployment)
- Python 3.10+ (For Streamlit dashboard)

### 1. Build & Run Tests locally with Maven
```bash
mvn clean test
mvn spring-boot:run
```
> The API server will start on `http://localhost:8080`. Swagger documentation is available at `http://localhost:8080/swagger-ui.html`.

### 2. Multi-Container Docker Setup
```bash
docker-compose up --build
```
This orchestrates:
- **Backend API Server**: `http://localhost:8080`
- **PostgreSQL Database**: `localhost:5432`
- **MongoDB Audit Database**: `localhost:27017`
- **Streamlit Visualization Dashboard**: `http://localhost:8501`

---

## 🧪 Automated Testing Suite

Included unit & integration tests verify fault tolerance under load:
- `IdempotencyServiceTest`: Validates request lock acquisition, duplicate key detection, and cached payload delivery.
- `PaymentProcessingServiceTest`: Tests payment lifecycle transitions and balance checks.
- `FaultToleranceRetryTest`: Asserts exponential backoff recovery during transient gateway outages.
- `ConcurrentPaymentTest`: Multi-threaded stress test (10 parallel threads) verifying **zero double-charging** and single execution under high contention.

---

## 📈 Horizontal Scalability Strategy

1. **Stateless API Tier**: API instances can be scaled horizontally behind an AWS ALB or NGINX load balancer without session stickiness.
2. **Partitioned Workers**: Worker thread pools consume messages from partition queues without cross-partition locks.
3. **Database Sharding**: Partitioning by `userId` / `idempotencyKey` enables sharding relational databases across distinct database nodes.
