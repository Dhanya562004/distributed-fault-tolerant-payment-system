# REST API Reference & Cloud Endpoint Specification

**Project**: ApexPay — Distributed Fault-Tolerant Payment Engine  
**Backend Base URL**: `https://apexpay-backend-sc5s.onrender.com`  
**Swagger Documentation**: `https://apexpay-backend-sc5s.onrender.com/swagger-ui.html`  
**Actuator Health**: `https://apexpay-backend-sc5s.onrender.com/actuator/health`  
**Live Telemetry**: `https://apexpay-backend-sc5s.onrender.com/api/v1/metrics`  
**Specification Standard**: OpenAPI 3.0 / Springdoc-OpenAPI  

---

## 1. Cloud Endpoints Summary

| Service / Capability | Verified Live URL | Purpose |
| :--- | :--- | :--- |
| **Backend Base URL** | [`https://apexpay-backend-sc5s.onrender.com`](https://apexpay-backend-sc5s.onrender.com) | Root domain hosting Spring Boot 3.3.4 payment microservice. |
| **Interactive Swagger UI** | [`https://apexpay-backend-sc5s.onrender.com/swagger-ui.html`](https://apexpay-backend-sc5s.onrender.com/swagger-ui.html) | Interactive browser UI to inspect schemas and execute test requests. |
| **OpenAPI 3.0 JSON Spec** | [`https://apexpay-backend-sc5s.onrender.com/v3/api-docs`](https://apexpay-backend-sc5s.onrender.com/v3/api-docs) | Machine-readable OpenAPI 3.0 schema definition. |
| **Actuator Health** | [`https://apexpay-backend-sc5s.onrender.com/actuator/health`](https://apexpay-backend-sc5s.onrender.com/actuator/health) | Application health, embedded database status, and readiness/liveness state. |
| **Live Telemetry Engine** | [`https://apexpay-backend-sc5s.onrender.com/api/v1/metrics`](https://apexpay-backend-sc5s.onrender.com/api/v1/metrics) | Real-time transaction counters, success rates, retries, and DLQ depths. |

---

## 2. Authentication & Header Requirements

ApexPay enforces strict header-based authentication and idempotency semantics depending on the sensitivity and side-effects of each endpoint.

### 2.1 Mutating Payment Endpoints (`POST /api/v1/payments`, `POST /api/v1/refunds`)
- **`X-Idempotency-Key` (String, Recommended)**:
  - Client-supplied unique token (UUID or deterministic merchant transaction ID).
  - Guarantees single-execution semantics under network retries.
  - Submitting an identical key with identical payload replays the cached response with the `Idempotent-Replayed: true` HTTP response header.
  - Submitting an identical key with a modified payload triggers `409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`).
  - Concurrent requests with the same in-flight key trigger `409 Conflict` (`IDEMPOTENCY_CONFLICT`).
  - Key expires after 24 hours (UTC server time), permitting safe reuse.
- **Content-Type**: `application/json`

### 2.2 keYset Pagination & Administrative Queries (`GET /api/v1/payments/transactions`)
- **`X-Api-Key` or `Authorization: Bearer <API_KEY>`**:
  - Required when `app.pagination.security.enabled` is `true`.
  - Configured in deployment via the environment variable `${PAGINATION_API_KEY}`.
  - Production deployments must inject strong entropy secrets via environment variables.
  - Prevents unauthorized bulk enumeration of payment records across tenants.

### 2.3 Webhook Event Delivery
- **`X-ApexPay-Signature`**:
  - Appended to outbound webhook POST requests from the `WebhookDeliveryWorker`.
  - Format: `t=<timestamp_seconds>,v1=<hex_hmac_sha256>`.
  - Signature computed over payload `${timestamp}.${payload}` using `${WEBHOOK_SIGNING_SECRET}`.

---

## 3. Endpoints Safe to Test (Read-Only & Observability)

The following endpoints are **completely safe to test** in the live cloud deployment as they perform non-destructive read operations without mutating ledger balances or executing financial debits:

### 3.1 Health Probe
- **Endpoint**: `GET /actuator/health`
- **Authentication**: None (Public)
- **Sample Request**:
  ```bash
  curl -s https://apexpay-backend-sc5s.onrender.com/actuator/health
  ```
- **Verified Response**:
  ```json
  {
    "status": "UP",
    "components": {
      "db": {
        "status": "UP",
        "details": {
          "database": "H2",
          "validationQuery": "isValid()"
        }
      },
      "diskSpace": {
        "status": "UP",
        "details": {
          "exists": true
        }
      },
      "livenessState": { "status": "UP" },
      "readinessState": { "status": "UP" }
    },
    "groups": ["liveness", "readiness"]
  }
  ```

### 3.2 Live Telemetry Metrics
- **Endpoint**: `GET /api/v1/metrics` (or alias `GET /metrics`)
- **Authentication**: None (Public)
- **Sample Request**:
  ```bash
  curl -s https://apexpay-backend-sc5s.onrender.com/api/v1/metrics
  ```
- **Exposed Metrics Payload**:
  ```json
  {
    "totalTransactions": 0,
    "successfulTransactions": 0,
    "failedTransactions": 0,
    "retryCount": 0,
    "idempotencyHits": 0,
    "dlqCount": 0,
    "successRatePercent": 0.0,
    "averageLatencyMs": 0.0,
    "activeQueueDepth": 0,
    "statusBreakdown": {
      "SUCCESS": 0,
      "FAILED": 0,
      "REFUNDED": 0,
      "PROCESSING": 0,
      "REFUND_PENDING": 0,
      "PENDING": 0,
      "REFUND_FAILED": 0
    },
    "timestamp": "2026-10-19T16:28:32.015850127Z"
  }
  ```

### 3.3 Seeded Demo User Accounts
- **Endpoint**: `GET /api/v1/users`
- **Authentication**: None (Public Read)
- **Sample Request**:
  ```bash
  curl -s https://apexpay-backend-sc5s.onrender.com/api/v1/users
  ```
- **Response**: Returns pre-seeded account profiles (`USR_ALICE`, `USR_BOB`, `USR_CHARLIE`, `USR_DAVID`) with starting demo balances.

### 3.4 Payment Transaction Listing
- **Endpoint**: `GET /api/v1/payments`
- **Authentication**: None (Public Read)
- **Sample Request**:
  ```bash
  curl -s https://apexpay-backend-sc5s.onrender.com/api/v1/payments
  ```
- **Response**: `[]` (Returns empty array upon fresh instance startup, or active ledger transactions).

### 3.5 Fault Injection Inspection
- **Endpoint**: `GET /api/v1/simulation/config` and `GET /api/v1/simulation/dlq`
- **Authentication**: None (Public Read)
- **Sample Request**:
  ```bash
  curl -s https://apexpay-backend-sc5s.onrender.com/api/v1/simulation/config
  curl -s https://apexpay-backend-sc5s.onrender.com/api/v1/simulation/dlq
  ```

---

## 4. Protected Financial Mutation Endpoints

> [!WARNING]
> Do NOT execute arbitrary unauthenticated or destructive payment requests against production environments. Mutation operations debit real or simulated user balances, acquire distributed idempotency locks, and transition payment state machines.

### 4.1 Initiate Payment Transaction
- **Method & Path**: `POST /api/v1/payments`
- **Headers**:
  - `Content-Type: application/json`
  - `X-Idempotency-Key: <unique_client_token>`
- **Request Body**:
  ```json
  {
    "userId": "USR_ALICE",
    "amount": 100.00,
    "currency": "USD",
    "paymentMethod": "CREDIT_CARD",
    "description": "Cloud hosting renewal",
    "asyncProcessing": false
  }
  ```
- **Responses**:
  - `201 Created`: Payment successfully authorized and balance debited.
  - `202 Accepted`: Payment queued into user-partitioned asynchronous worker pool (`asyncProcessing: true`).
  - `200 OK` (with `Idempotent-Replayed: true` header): Replayed cached result for repeated identical idempotency key.
  - `409 Conflict`: Duplicate in-flight request or payload mismatch for active key.
  - `422 Unprocessable Entity`: Permanent business rejection (e.g. `INSUFFICIENT_FUNDS`, fail-fast on Attempt 1).

### 4.2 Initiate Refund
- **Method & Path**: `POST /api/v1/refunds`
- **Headers**:
  - `Content-Type: application/json`
  - `X-Idempotency-Key: <unique_client_token>`
- **Request Body**:
  ```json
  {
    "paymentId": "PAY_A1B2C3D4E5F67890",
    "amount": 50.00,
    "reason": "Customer cancellation"
  }
  ```
- **Responses**:
  - `201 Created`: Refund executed, balance credited back, outbox event written.
  - `422 Unprocessable Entity`: Refund exceeds remaining transaction balance or invalid payment state.

### 4.3 Keyset Cursor Pagination
- **Method & Path**: `GET /api/v1/payments/transactions`
- **Query Parameters**:
  - `limit`: Integer (default 20, max 100).
  - `cursor`: Opaque signed cursor token (`<base64(createdAt|paymentId)>.<hmac_sha256>`).
  - `status`: Filter by transaction status (`SUCCESS`, `FAILED`, `REFUNDED`).
  - `from` / `to`: ISO-8601 UTC timestamp filters.
- **Headers**:
  - `X-Api-Key: <CONFIGURED_API_KEY>` (or `Authorization: Bearer <CONFIGURED_API_KEY>`)

---

## 5. Security & Secret Protection Guidelines

1. **Zero Credential Exposure**: Never commit API keys, HMAC signing secrets, database credentials, or production tokens into version control.
2. **Environment Injections**: All production secrets are read from environment variables (`PAGINATION_API_KEY`, `PAGINATION_SIGNING_SECRET`, `WEBHOOK_SIGNING_SECRET`, `SPRING_DATASOURCE_*`).
3. **Restricted Actuator Surface**: Only safe monitoring endpoints (`health`, `info`, `metrics`, `prometheus`) are exposed. Sensitive endpoints (`env`, `beans`, `heapdump`, `shutdown`) are strictly disabled in `application.yml`.
4. **Idempotency Payload Privacy**: The idempotency engine stores SHA-256 digests of payloads rather than unencrypted sensitive credentials.
