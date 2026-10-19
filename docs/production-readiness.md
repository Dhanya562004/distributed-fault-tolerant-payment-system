# Production Readiness & Cloud Deployment Specification

**Project**: ApexPay — Distributed Fault-Tolerant Payment Engine  
**Live Backend Service**: Render Web Service (`https://apexpay-backend-sc5s.onrender.com`)  
**Live Dashboard**: Streamlit Community Cloud (`https://distributed-fault-tolerant-payment-system-5qg2tjnkfrk7tvbqxzsz.streamlit.app/`)  
**Document Classification**: Enterprise Architecture & Operations Review  

---

## 1. Cloud Infrastructure & Hosting Topology

ApexPay is deployed in a decoupled, cloud-native architecture optimized for live demonstration, recruiter inspection, and automated verification:

```
[Web Client / Recruiter]
           |
           | TLS 1.3 (HTTPS)
           v
[Streamlit Community Cloud]  --> Python 3.11 Runtime / Interactive Dashboard
           |
           | Outbound REST (HTTPS)
           v
   [Render Cloud Web Service]  --> Docker Container (Eclipse Temurin 17 JRE Alpine)
           |                       Spring Boot 3.3.4 Application Engine
           |
           +---> Actuator Health (/actuator/health)
           +---> OpenAPI / Swagger UI (/swagger-ui.html)
           +---> Telemetry Actuator (/api/v1/metrics)
           +---> Transactional Outbox & Webhook Worker
           |
           v
[Persistence Layer]
  - Live Deployment: Embedded H2 Database (PostgreSQL dialect)
  - Enterprise Profile: Managed PostgreSQL 16 (Configured in application-prod.yml)
```

---

## 2. Service Deployment Configurations

### 2.1 Render Spring Boot Microservice
- **Deployment Platform**: Render Free Web Service.
- **Containerization**: Multi-stage Docker build:
  - **Build Stage**: `maven:3.9.6-eclipse-temurin-17-alpine` (`mvn package -DskipTests`).
  - **Runtime Stage**: `eclipse-temurin:17-jre-alpine` running optimized `app.jar`.
- **Process Security**: Non-root container execution under dedicated Linux user `appuser` (group `appgroup`).
- **Network Interface**: Listens on port 8080 (container exposed port), fronted by Render's automated TLS 1.3 reverse proxy.
- **Resources**: 512 MB RAM, 0.1 CPU core allocation (free tier).

### 2.2 Streamlit Cloud Dashboard
- **Deployment Platform**: Streamlit Community Cloud.
- **Runtime Environment**: Python 3.11 with dependencies installed via `dashboard/requirements.txt` (`streamlit`, `requests`, `pandas`, `plotly`).
- **Resilient HTTP Client**: Configured in `dashboard/app.py` via `urllib3.util.Retry` with connection pooling, automatic retries for transient HTTP errors (500, 502, 503, 504), and exponential backoff factor.
- **Auto-Detection & Fallback Strategy**:
  - The dashboard continuously probes backend readiness via `GET /actuator/health` and `GET /api/v1/metrics`.
  - When the live Render microservice is awake and operational, it displays the green **SPRING BOOT CONNECTED** badge and routes transactions to live REST endpoints.
  - If Render is asleep or cold-starting, the UI gracefully switches to the high-fidelity in-memory visual simulation mode without throwing unhandled exceptions.

---

## 3. Database Persistence Configuration

### 3.1 Verified Live Deployment Persistence (H2 In-Memory)
- **Active Profile**: `dev` (default active profile in `application.yml`).
- **JDBC URL**: `jdbc:h2:mem:payments_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL`
- **Driver**: `org.h2.Driver`
- **Dialect**: `org.hibernate.dialect.H2Dialect`
- **DDL Mode**: `create-drop` with automatic DDL table creation and seed data execution from `src/main/resources/data.sql`.
- **Live Health Verification**:
  ```bash
  curl -s https://apexpay-backend-sc5s.onrender.com/actuator/health
  # Response confirms: "db":{"status":"UP","details":{"database":"H2","validationQuery":"isValid()"}}
  ```

### 3.2 Enterprise Production Profile (`application-prod.yml`)
- **Target Storage**: Managed PostgreSQL 16.
- **JDBC URL**: `${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/payment_db}`
- **Driver**: `org.postgresql.Driver`
- **Dialect**: `org.hibernate.dialect.PostgreSQLDialect`
- **Connection Pool**: HikariCP with production tuning:
  - `maximum-pool-size: 20`
  - `minimum-idle: 5`
  - `idle-timeout: 300000` (5 minutes)
  - `connection-timeout: 20000` (20 seconds)
- **DDL Mode**: `validate` (strict schema validation ensuring migrations manage schema changes).
- **Audit Store**: Configurable MongoDB integration (`${SPRING_DATA_MONGODB_URI}`).

### 3.3 CI/CD Verification (PostgreSQL 16 Testcontainers)
While free-tier cloud hosting utilizes H2 to minimize hosting costs, 100% of the relational data model, transaction isolation levels, pessimistic row-level locks (`SELECT FOR UPDATE`), JPA `@Version` optimistic locks, and a 1,000-request concurrent storm are verified in GitHub Actions Stage B against real PostgreSQL 16 using Testcontainers.

---

## 4. Security & Endpoint Exposure Analysis

### 4.1 Public Endpoints Review
The following endpoints are intentionally exposed without authentication to facilitate public evaluation and live telemetry:

| Endpoint | Method | Data Exposed | Security Assessment |
| :--- | :--- | :--- | :--- |
| `/` | `GET` | Service name and status string | Safe. No internal topology or credentials revealed. |
| `/actuator/health` | `GET` | Health indicator (`UP`), component statuses, disk status | Safe. Actuator details are sanitized; no connection strings or credentials. |
| `/swagger-ui.html` | `GET` | Swagger UI HTML interface | Safe for demo. Documents REST schemas. |
| `/v3/api-docs` | `GET` | OpenAPI 3.0 JSON specification | Safe for demo. Standard schema metadata. |
| `/api/v1/metrics` | `GET` | Transaction counts, success rate, DLQ count, latencies | Safe. Aggregate telemetry only. **Zero PII or customer data**. |
| `/api/v1/users` | `GET` | Demo user accounts (`USR_ALICE`, `USR_BOB`, etc.) | Safe. Synthetic demo balances only. |
| `/api/v1/payments` | `GET` | Current transaction records in ephemeral ledger | Safe. Synthetic demo records only. |
| `/api/v1/simulation/*` | `GET` | Active fault injection configuration & DLQ count | Safe. Simulation control for chaos testing. |

### 4.2 Swagger UI Security Considerations
- **Current Live Posture**: Swagger UI is publicly accessible to provide immediate interactive evaluation for recruiters and developers.
- **Enterprise Hardening Recommendation**: For production enterprise banking deployments, OpenAPI documentation should be placed behind internal corporate SSO/VPN gates, disabled in the production profile, or restricted via IP allowlists in the API gateway.

### 4.3 Spring Boot Actuator Security
- **Enforced Principle of Least Privilege**: In `application.yml`, endpoint exposure is explicitly restricted:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info,metrics,prometheus
  ```
- **Dangerous Actuators Disabled**: Sensitive endpoints that could expose internal JVM state or allow remote management (`/actuator/env`, `/actuator/beans`, `/actuator/heapdump`, `/actuator/threaddump`, `/actuator/shutdown`) are strictly disabled.

### 4.4 Telemetry Security
- The `/api/v1/metrics` endpoint was audited to confirm it exposes exclusively aggregate metrics:
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
    "statusBreakdown": { ... }
  }
  ```
- No card numbers, user names, email addresses, transaction descriptions, or database hostnames are leaked.

### 4.5 Backend Authentication
- **Pagination Protection**: `GET /api/v1/payments/transactions` requires `X-Api-Key` or `Authorization: Bearer <token>` when enabled.
- **Idempotency Protection**: `POST /api/v1/payments` requires `X-Idempotency-Key` to prevent double-spending.
- **Webhook Integrity**: Outbound webhooks are cryptographically signed using HMAC-SHA256 (`X-ApexPay-Signature`) to guarantee authenticity and prevent tampering.

---

## 5. Environment Variable Reference

The application requires or supports the following environment variables across deployment stages:

| Variable | Description | Default (Dev) | Production Target |
| :--- | :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Active Spring environment profile | `dev` | `prod` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC connection URL | N/A (H2 in dev) | `jdbc:postgresql://<host>:5432/<db>` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL database user | `sa` | Secure DB user |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL database password | `password` | Injected secret |
| `SPRING_DATA_MONGODB_URI` | MongoDB connection URI for audit trail | N/A (in-memory buffer) | `mongodb://<host>:27017/payment_audit_db` |
| `PAGINATION_API_KEY` | Admin API key for keyset cursor pagination | `apexpay_admin_secret_key_2026` | Injected secret (64+ chars) |
| `PAGINATION_SIGNING_SECRET` | Secret key for signing cursor tokens | `apexpay_cursor_signing_secret_dev_2026` | Injected secret (64+ chars) |
| `WEBHOOK_SIGNING_SECRET` | Secret key for signing outbound webhooks | Empty string | Injected secret (64+ chars) |
| `WEBHOOK_DESTINATION_URL` | Destination webhook receiver endpoint | Empty string | HTTPS destination URL |
| `WEBHOOK_ENABLED` | Toggle for scheduled outbox delivery worker | `false` | `true` |
| `BACKEND_API_URL` | Base URL used by Streamlit Dashboard | `https://apexpay-backend-sc5s.onrender.com` | Live backend URL |

---

## 6. Free-Tier Hosting Behaviors & Cold-Start Mitigation

When evaluating the live deployment on Render free tier, the following behavioral characteristics apply:

1. **Inactivity Sleep Cycle**:
   - Render automatically spins down free web service instances after **15 minutes** of HTTP inactivity.
2. **Cold-Start JVM Spin-Up**:
   - The first request to a sleeping instance incurs a **30 to 60-second delay** while Render provisions the container and boots the Java 17 JVM and Spring Boot application context.
   - Subsequent requests execute with sub-50ms latency.
3. **Ephemeral In-Memory Persistence**:
   - Because the live Render instance runs with in-memory H2, container restarts or redeployments re-initialize the database from `data.sql`, resetting transaction counters to 0 and re-seeding the 4 demo users.
4. **Client-Side Graceful Degradation**:
   - The Streamlit Dashboard is engineered with non-blocking status probes and a 8.0-second timeout. If the backend is warming up, the dashboard displays "Waiting for Backend" or enables standalone visual simulation mode, allowing instant exploration while Render completes its JVM warm-up.
