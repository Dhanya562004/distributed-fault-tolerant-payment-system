# Production Keyset Pagination Architecture & Specification

**Project**: ApexPay — Distributed Fault-Tolerant Payment Engine  
**Feature**: Step 5 — Production-Style Cursor-Based Payment Transaction Pagination  
**Dialects Supported**: PostgreSQL 16 / H2 (PostgreSQL Mode)  

---

## 1. Architecture Overview & Problem Statement

In high-throughput financial transaction processing, traditional `OFFSET` / `LIMIT` SQL pagination introduces critical production vulnerabilities:

1. **Quadratic Query Degradation ($O(N)$ Scan Cost)**:  
   `OFFSET 500000 LIMIT 20` forces the database engine to scan and discard 500,000 index pages before returning 20 records.
2. **Page-Drift & Duplicate/Skipped Transactions**:  
   If new payment transactions are authorized while a user navigates between pages, offsets shift dynamically. A client requesting page 2 will either see transactions already observed on page 1 (duplicates) or miss valid transactions entirely (skips).
3. **Unstable Ordering on Equal Timestamps**:  
   High-concurrency payment engines process hundreds of transactions within the same millisecond or microsecond. Ordering solely by `created_at DESC` produces non-deterministic results across replicas and query executions.

### ApexPay Keyset Architecture

ApexPay solves these challenges through **Deterministic Keyset Pagination** using composite keys `(created_at DESC, id DESC)`:

- **Zero OFFSET Execution**: Queries use strict bounding predicates `WHERE created_at < :cursorTime OR (created_at = :cursorTime AND id < :cursorId)`.
- **$O(\log N + K)$ Indexed Seek**: Navigates directly via composite B-tree index `idx_payments_created_at_id`, executing a logarithmic index seek ($O(\log N)$) followed by retrieving $K$ page records, completely eliminating $O(M)$ linear offset scans.
- **Stable Windowing**: Newly inserted transactions with higher timestamps appear only on subsequent first-page requests and never distort in-progress pagination cursors.
- **Cryptographic Cursor Integrity**: Cursors are opaque, URL-safe Base64-encoded, versioned (`v1`), filter-bound (SHA-256), and signed with server-side HMAC-SHA256 to eliminate client tampering.

---

## 2. API Endpoint Specification

### `GET /api/v1/payments/transactions`

Retrieves a chronologically ordered, keyset-paginated slice of payment transactions.

#### HTTP Headers
| Header | Required | Type | Description |
| :--- | :---: | :--- | :--- |
| `X-Api-Key` | Conditional | String | Server administrative API key (configurable via `app.pagination.security.api-key`). Platform-wide access. |
| `Authorization` | Conditional | String | Bearer or raw authorization token: `Bearer <token>`. |
| `X-Tenant-Id` | Optional | String | Tenant / Account scope identifier bound to cursor verification. (Note: Acts as a cursor filter bind parameter; customer-level SQL scoping requires upstream identity token isolation). |

#### Query Parameters
| Parameter | Type | Required | Default | Bounds | Description |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `limit` | Integer | No | `20` | `1` to `100` | Number of transaction items requested per page. Values `< 1` or `> 100` return HTTP 400. |
| `cursor` | String | No | `null` | Max 1024 chars | Opaque, signed URL-safe Base64 cursor from `pagination.nextCursor` of previous page. |
| `status` | String | No | `null` | Enum | Filter by `PaymentStatus` (`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`). |
| `from` | String | No | `null` | ISO-8601 UTC | Lower creation timestamp bound inclusive (e.g. `2026-10-19T00:00:00Z`). |
| `to` | String | No | `null` | ISO-8601 UTC | Upper creation timestamp bound inclusive (e.g. `2026-10-19T23:59:59Z`). |

---

## 3. Cursor Format, Filter Binding & Security

### 3.1 Cursor Payload Schema (`v1`)

```json
{
  "v": "v1",
  "c": "2026-10-19T10:15:30.123456Z",
  "id": 10582,
  "fh": "a9f82bc4e1...",
  "sig": "3d9c84e1b7..."
}
```

- **`v` (Version)**: Protocol version (`v1`). Unsupported versions return HTTP 400.
- **`c` (CreatedAt)**: ISO-8601 UTC timestamp of the tail record in the returned page.
- **`id` (PrimaryKey ID)**: Database primary key ID of the tail record, acting as the deterministic tie-breaker.
- **`fh` (Filter Hash)**: 256-bit SHA-256 hex fingerprint derived from canonical query parameters:
  $$\text{FilterHash} = \text{SHA-256}(\text{status} \parallel "|" \parallel \text{from} \parallel "|" \parallel \text{to} \parallel "|" \parallel \text{scope})$$
- **`sig` (HMAC Signature)**: Hex-encoded HMAC-SHA256 signature calculated over:
  $$\text{CanonicalPayload} = \text{"v1|" } \parallel \text{createdAt} \parallel "|" \parallel \text{id} \parallel "|" \parallel \text{filterHash}$$

### 3.2 Security Protections

1. **Tamper Resistance**: Any alteration of `createdAt`, `id`, or `fh` invalidates the HMAC signature. Verification uses constant-time comparison (`MessageDigest.isEqual`) to eliminate timing side-channel attacks.
2. **Filter Binding Enforcement**: A cursor generated for `status=SUCCESS` cannot be submitted with `status=FAILED` or a different date range. Any filter shift returns HTTP 400 `INVALID_CURSOR`.
3. **Payload Sanitization**: Reject cursors exceeding 1,024 characters, invalid Base64 characters, unparseable JSON, negative IDs, or malformed ISO timestamps.
4. **Secret Isolation**: Cursors are signed using a dedicated server secret (`app.pagination.signing-secret`), isolated from webhook signing secrets (`app.webhook.signing-secret`).

---

## 4. Keyset SQL Query Execution & Index Optimization

### 4.1 Database Keyset Query

When navigating with a cursor $(c_{\text{time}}, c_{\text{id}})$:

```sql
SELECT p.*
FROM payments p
WHERE (:status IS NULL OR p.status = :status)
  AND (:fromDate IS NULL OR p.createdAt >= :fromDate)
  AND (:toDate IS NULL OR p.createdAt <= :toDate)
  AND (
        p.created_at < :cursorCreatedAt
        OR (p.created_at = :cursorCreatedAt AND p.id < :cursorId)
      )
ORDER BY p.created_at DESC, p.id DESC
LIMIT :limitPlusOne;
```

### 4.2 Database Index Design

To guarantee single-digit millisecond response times on large tables without sequential scans, the following composite index is registered in `schema.sql` and Hibernate entities:

```sql
CREATE INDEX IF NOT EXISTS idx_payments_created_at_id ON payments(created_at DESC, id DESC);
```

- **Write Overhead Consideration**: B-tree index maintenance is minimized by indexing only `(created_at DESC, id DESC)`. Status filtering leverages existing single-column index `idx_payments_status` or index intersection bitmap scans in PostgreSQL 16.
- **Dialect Compatibility**: Fully verified and compatible with PostgreSQL 16 B-tree index ordering and H2 in-memory PostgreSQL emulation mode.

---

## 5. Request & Response Examples

### 5.1 Request: Initial Page (Default Limit 20)

```http
GET /api/v1/payments/transactions HTTP/1.1
Host: api.apexpay.com
X-Api-Key: apexpay_admin_secret_key_2026
```

#### Response (`200 OK`):
```json
{
  "data": [
    {
      "id": "PAY_98F1A2B3C4D5E6F7",
      "paymentId": "PAY_98F1A2B3C4D5E6F7",
      "userId": "USR_ALICE",
      "amount": 250.0000,
      "currency": "USD",
      "status": "SUCCESS",
      "paymentMethod": "CREDIT_CARD",
      "description": "Enterprise license renewal",
      "createdAt": "2026-10-19T14:30:00Z",
      "updatedAt": "2026-10-19T14:30:00.025Z"
    }
  ],
  "pagination": {
    "limit": 20,
    "hasMore": true,
    "nextCursor": "eyJ2IjoidjEiLCJjIjoiMjAyNi0xMC0xOVQxNDozMDowMFoiLCJpZCI6NDIsImZoIjoiYTM0Yi4uLiIsInNpZyI6IjdlOTEuLi4ifQ"
  }
}
```

### 5.2 Request: Subsequent Page Using Next Cursor

```http
GET /api/v1/payments/transactions?limit=20&cursor=eyJ2IjoidjEiLCJjIjoiMjAyNi0xMC0xOVQxNDozMDowMFoiLCJpZCI6NDIsImZoIjoiYTM0Yi4uLiIsInNpZyI6IjdlOTEuLi4ifQ HTTP/1.1
Host: api.apexpay.com
X-Api-Key: apexpay_admin_secret_key_2026
```

---

## 6. Error Handling & HTTP Status Matrix

| Scenario | HTTP Status | Error Code | Description |
| :--- | :---: | :--- | :--- |
| **Missing Credentials** | `401 Unauthorized` | `UNAUTHORIZED` | Missing `X-Api-Key` or `Authorization` header when security is enabled. |
| **Invalid Credentials** | `403 Forbidden` | `FORBIDDEN` | Provided API key failed constant-time verification. |
| **Invalid Limit (< 1 or > 100)** | `400 Bad Request` | `INVALID_PAGINATION_FILTER` | `limit` must be an integer between 1 and 100. |
| **Non-Numeric Limit** | `400 Bad Request` | `INVALID_PARAMETER` | Method argument type mismatch for `limit`. |
| **Malformed Cursor** | `400 Bad Request` | `INVALID_CURSOR` | Base64 decode error, unparseable JSON, or missing fields. |
| **Unsupported Cursor Version** | `400 Bad Request` | `INVALID_CURSOR` | Cursor version is not `v1`. |
| **Tampered Signature** | `400 Bad Request` | `INVALID_CURSOR` | Cryptographic signature verification failed. |
| **Filter Binding Mismatch** | `400 Bad Request` | `INVALID_CURSOR` | Query filters changed between consecutive pages. |
| **Invalid Status Filter** | `400 Bad Request` | `INVALID_PAGINATION_FILTER` | Unrecognized status string (e.g. `UNKNOWN`). |
| **Invalid Date Format** | `400 Bad Request` | `INVALID_PAGINATION_FILTER` | Malformed ISO-8601 UTC timestamp. |
| **Invalid Date Range** | `400 Bad Request` | `INVALID_PAGINATION_FILTER` | `from` timestamp is after `to` timestamp. |

---

## 7. Stable Pagination vs. Snapshot Isolation Note

Keyset pagination guarantees **window stability**: newer records inserted ahead of the cursor boundary do not shift the cursor index, preventing duplicate and skipped records. 

However, keyset pagination is **not database snapshot isolation**. If a transaction that precedes the current cursor is updated or deleted while pagination is underway, the state reflects committed data at query execution time. No claims of full cross-request snapshot isolation are made without multi-version snapshot locks.
