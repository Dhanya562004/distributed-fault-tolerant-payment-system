# REST API Specification & Developer Guide

**Project**: Distributed Fault-Tolerant Payment System  
**Base URL**: `http://localhost:8080`  
**OpenAPI / Swagger UI**: `http://localhost:8080/swagger-ui.html`  
**OpenAPI JSON Specification**: `http://localhost:8080/v3/api-docs`  

---

## 1. Overview & Conventions

The system exposes RESTful APIs designed according to enterprise banking standards. All endpoints conform to:

- **JSON Payloads**: Request and response bodies are serialized in JSON UTF-8.
- **Idempotency Guarantees**: Mutation endpoints (`POST /api/v1/payments`, `POST /api/v1/refunds`) accept an `X-Idempotency-Key` HTTP header.
- **24-Hour Retention Period**: Idempotency keys have a configurable 24-hour retention period (`app.idempotency.retention-hours: 24`). Re-submitting an identical request within this window returns the original cached response.
- **`Idempotent-Replayed: true` Header**: When a completed payment or refund response is served from the cache, the HTTP response includes `Idempotent-Replayed: true`. Original executions do NOT return this header.
- **Expired-Key Reuse Semantics**: Once a key reaches its 24-hour boundary (server-side UTC), it expires. Clients may safely reuse the expired key as a new, distinct payment request. Atomic database locking (`UPDATE ... WHERE expires_at <= :now`) prevents concurrent double-charges.
- **Payload Mismatch Protection**: Submitting an unexpired key with a modified request body triggers an immediate `409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`), without the replay header.
- **Pagination & Sorting**: Collection endpoints accept optional `page` (0-indexed, default 0) and `size` (default 20, max 100) query parameters.

---

## 2. Idempotency Lifecycle & HTTP Header Examples

### 2.1 Initial Payment Execution (Fresh Request)
```http
POST /api/v1/payments HTTP/1.1
Host: api.apexpay.com
X-Idempotency-Key: IDEM_TX_20261018_001
Content-Type: application/json

{
  "userId": "USR_ALICE",
  "amount": 250.00,
  "currency": "USD",
  "paymentMethod": "CREDIT_CARD",
  "description": "Enterprise software license"
}
```

#### Response (`201 Created` — No Replay Header):
```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "paymentId": "PAY_A1B2C3D4E5F67890",
  "idempotencyKey": "IDEM_TX_20261018_001",
  "userId": "USR_ALICE",
  "amount": 250.00,
  "currency": "USD",
  "status": "SUCCESS",
  "paymentMethod": "CREDIT_CARD",
  "description": "Enterprise software license",
  "failureReason": null,
  "retryCount": 0,
  "createdAt": "2026-10-18T10:00:00Z",
  "updatedAt": "2026-10-18T10:00:00.045Z",
  "isCachedIdempotentResponse": false
}
```

### 2.2 Replay within 24 Hours (Cached Response)
```http
POST /api/v1/payments HTTP/1.1
Host: api.apexpay.com
X-Idempotency-Key: IDEM_TX_20261018_001
Content-Type: application/json

{
  "userId": "USR_ALICE",
  "amount": 250.00,
  "currency": "USD",
  "paymentMethod": "CREDIT_CARD",
  "description": "Enterprise software license"
}
```

#### Response (`201 Created` — With `Idempotent-Replayed: true`):
```http
HTTP/1.1 201 Created
Idempotent-Replayed: true
Content-Type: application/json

{
  "paymentId": "PAY_A1B2C3D4E5F67890",
  "idempotencyKey": "IDEM_TX_20261018_001",
  "userId": "USR_ALICE",
  "amount": 250.00,
  "currency": "USD",
  "status": "SUCCESS",
  "paymentMethod": "CREDIT_CARD",
  "description": "Enterprise software license",
  "failureReason": null,
  "retryCount": 0,
  "createdAt": "2026-10-18T10:00:00Z",
  "updatedAt": "2026-10-18T10:00:00.045Z",
  "isCachedIdempotentResponse": true
}
```

### 2.3 Reusing Expired Key After 24 Hours (Fresh Execution)
At $T_0 + 25\text{ hours}$, the key has expired. Submitting a new request with the same key generates a brand new payment:
```http
POST /api/v1/payments HTTP/1.1
Host: api.apexpay.com
X-Idempotency-Key: IDEM_TX_20261018_001
Content-Type: application/json

{
  "userId": "USR_ALICE",
  "amount": 300.00,
  "currency": "USD",
  "paymentMethod": "CREDIT_CARD",
  "description": "Renewal after 24h"
}
```

#### Response (`201 Created` — New Payment ID, No Replay Header):
```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "paymentId": "PAY_F9E8D7C6B5A43210",
  "idempotencyKey": "IDEM_TX_20261018_001",
  "userId": "USR_ALICE",
  "amount": 300.00,
  "currency": "USD",
  "status": "SUCCESS",
  "paymentMethod": "CREDIT_CARD",
  "description": "Renewal after 24h",
  "failureReason": null,
  "retryCount": 0,
  "createdAt": "2026-10-19T11:00:00Z",
  "updatedAt": "2026-10-19T11:00:00.038Z",
  "isCachedIdempotentResponse": false
}
```

---

## 2. Global Error Structure

All error responses return a standardized schema:

```json
{
  "status": 409,
  "error": "IDEMPOTENCY_PAYLOAD_MISMATCH",
  "message": "Request with idempotency key [IDEM_9876] was previously executed with a different payload.",
  "path": "/api/v1/payments",
  "traceId": "9b12a4e1",
  "details": [
    "An operation with this idempotency key was previously processed with a different request body digest."
  ],
  "timestamp": "2026-10-10T05:07:18.710Z"
}
```

### Standard HTTP Status Codes

| Status Code | Meaning | Typical Scenario |
| :--- | :--- | :--- |
| `200 OK` | Request succeeded | Successful GET query or cached idempotent replay |
| `201 CREATED` | Resource created | Synchronous payment or refund processed |
| `202 ACCEPTED` | Accepted for async processing | Payment dispatched to partitioned worker queue |
| `400 BAD REQUEST` | Validation failed | Negative amount, missing mandatory fields |
| `404 NOT FOUND` | Resource not found | Payment ID or User ID does not exist |
| `409 CONFLICT` | Idempotency or concurrency conflict | Reused key with payload mismatch, in-flight race condition |
| `422 UNPROCESSABLE` | Business rule invariant violated | Insufficient user balance, refunding non-success transaction |
| `500 INTERNAL ERROR` | Unhandled infrastructure failure | Unexpected server error |

---

## 3. Payment Endpoints

### 3.1 Initiate Payment
- **Method**: `POST`
- **Path**: `/api/v1/payments`
- **Header**: `X-Idempotency-Key` (Optional; can also be provided in body)

#### Request Payload
```json
{
  "idempotencyKey": "IDEM_TX_20261010_001",
  "userId": "USR_ALICE",
  "amount": 250.00,
  "currency": "USD",
  "paymentMethod": "CREDIT_CARD",
  "description": "Enterprise cloud subscription",
  "asyncProcessing": false
}
```

#### Response Payload (`201 CREATED`)
```json
{
  "paymentId": "PAY_9B2C3D4E5F6A7B8C",
  "idempotencyKey": "IDEM_TX_20261010_001",
  "userId": "USR_ALICE",
  "amount": 250.00,
  "currency": "USD",
  "status": "SUCCESS",
  "paymentMethod": "CREDIT_CARD",
  "description": "Enterprise cloud subscription",
  "failureReason": null,
  "retryCount": 0,
  "createdAt": "2026-10-10T05:07:18.000Z",
  "updatedAt": "2026-10-10T05:07:18.050Z",
  "isCachedIdempotentResponse": false
}
```

---

### 3.2 Fetch Payment Status
- **Method**: `GET`
- **Path**: `/api/v1/payments/{paymentId}`

#### Response (`200 OK`)
```json
{
  "paymentId": "PAY_9B2C3D4E5F6A7B8C",
  "status": "SUCCESS",
  "amount": 250.00,
  "currency": "USD",
  "userId": "USR_ALICE"
}
```

---

### 3.3 List All Payments (Paginated)
- **Method**: `GET`
- **Path**: `/api/v1/payments?page=0&size=20`

#### Response (`200 OK`)
```json
[
  {
    "paymentId": "PAY_9B2C3D4E5F6A7B8C",
    "idempotencyKey": "IDEM_TX_20261010_001",
    "userId": "USR_ALICE",
    "amount": 250.00,
    "status": "SUCCESS"
  }
]
```

---

## 4. Refund Endpoints

### 4.1 Process Refund
- **Method**: `POST`
- **Path**: `/api/v1/refunds`
- **Header**: `X-Idempotency-Key` (Optional)

#### Request Payload
```json
{
  "paymentId": "PAY_9B2C3D4E5F6A7B8C",
  "idempotencyKey": "IDEM_REF_20261010_001",
  "amount": 100.00,
  "reason": "Customer item return"
}
```

#### Response (`201 CREATED`)
```json
{
  "refundId": "REF_1A2B3C4D5E6F7A8B",
  "paymentId": "PAY_9B2C3D4E5F6A7B8C",
  "userId": "USR_ALICE",
  "amount": 100.00,
  "status": "REFUNDED",
  "reason": "Customer item return",
  "createdAt": "2026-10-10T05:07:20.000Z"
}
```

---

## 5. Audit & Observability Endpoints

### 5.1 Query Audit Logs
- **Method**: `GET`
- **Path**: `/api/v1/audit/logs`

```json
[
  {
    "id": "67076b9e4a3c2e1b",
    "paymentId": "PAY_9B2C3D4E5F6A7B8C",
    "previousStatus": "PENDING",
    "newStatus": "SUCCESS",
    "action": "PAYMENT_AUTHORIZED",
    "workerId": "WORKER_DIRECT",
    "timestamp": "2026-10-10T05:07:18.045Z"
  }
]
```

### 5.2 Real-Time Metrics
- **Method**: `GET`
- **Path**: `/api/v1/metrics`

```json
{
  "totalTransactions": 1420,
  "successfulTransactions": 1395,
  "failedTransactions": 25,
  "idempotencyHits": 84,
  "retriesAttempted": 12,
  "averageLatencyMs": 42.6,
  "deadLetterQueueSize": 0
}
```
