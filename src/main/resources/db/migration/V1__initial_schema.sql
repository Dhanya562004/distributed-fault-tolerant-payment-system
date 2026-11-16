-- =============================================================================
-- Flyway Database Migration: V1__initial_schema.sql
-- Baseline relational schema for distributed fault-tolerant payment system
-- Compatible with PostgreSQL 16+ and H2 (PostgreSQL Mode)
-- =============================================================================

-- 1. Currencies Table (ISO 4217 Currency Codes & Minor Unit Decimal Places)
CREATE TABLE IF NOT EXISTS currencies (
    code VARCHAR(8) PRIMARY KEY,
    decimal_places INT NOT NULL DEFAULT 2,
    name VARCHAR(64) NOT NULL
);

-- 2. API Keys Table (Multi-tenant Customer Authentication)
CREATE TABLE IF NOT EXISTS api_keys (
    id BIGSERIAL PRIMARY KEY,
    key_value VARCHAR(128) NOT NULL UNIQUE,
    customer_id VARCHAR(64) NOT NULL,
    customer_name VARCHAR(128),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_api_keys_key ON api_keys(key_value);
CREATE INDEX IF NOT EXISTS idx_api_keys_customer_id ON api_keys(customer_id);

-- 3. Users Table (Customer Wallet Accounts with Optimistic Locking Version)
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    user_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_user_code ON users(user_code);

-- 4. Payments Table (Strict 5-State Finite State Machine)
CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    status VARCHAR(32) NOT NULL,
    payment_method VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    failure_reason VARCHAR(512),
    retry_count INT NOT NULL DEFAULT 0,
    request_id VARCHAR(64),
    lock_version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_payment_id ON payments(payment_id);
CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key ON payments(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payments(user_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);
CREATE INDEX IF NOT EXISTS idx_payments_request_id ON payments(request_id);
CREATE INDEX IF NOT EXISTS idx_payments_created_at_id ON payments(created_at DESC, id DESC);

-- 5. Idempotency Keys Table (Scoped per Tenant API Key with 24-hr Expiry)
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    api_key VARCHAR(128) NOT NULL DEFAULT 'default',
    key_value VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_body TEXT,
    http_status_code INT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_idempotency_api_key_value ON idempotency_keys(api_key, key_value);
CREATE INDEX IF NOT EXISTS idx_idempotency_key_value ON idempotency_keys(key_value);
CREATE INDEX IF NOT EXISTS idx_idempotency_expires_at ON idempotency_keys(expires_at);

-- 6. Refund Transactions Table
CREATE TABLE IF NOT EXISTS refunds (
    id BIGSERIAL PRIMARY KEY,
    refund_id VARCHAR(64) NOT NULL UNIQUE,
    payment_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_refunds_refund_id ON refunds(refund_id);
CREATE INDEX IF NOT EXISTS idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX IF NOT EXISTS idx_refunds_user_id ON refunds(user_id);

-- 7. Outbox Events Table (Transactional Outbox Pattern with Leased Workers)
CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    request_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1024),
    locked_until TIMESTAMP WITH TIME ZONE,
    lease_owner VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_event_id ON outbox_events(event_id);
CREATE INDEX IF NOT EXISTS idx_outbox_status_next_attempt ON outbox_events(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id ON outbox_events(aggregate_id);
CREATE INDEX IF NOT EXISTS idx_outbox_request_id ON outbox_events(request_id);

-- 8. Double-Entry Ledger Table (Immutable & Append-Only)
CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    entry_id VARCHAR(64) NOT NULL UNIQUE,
    transaction_id VARCHAR(64) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    entry_side VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ledger_tx_side UNIQUE (transaction_id, entry_side)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ledger_entry_id ON ledger_entries(entry_id);
CREATE INDEX IF NOT EXISTS idx_ledger_account_id ON ledger_entries(account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_tx_id ON ledger_entries(transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_currency_created ON ledger_entries(currency, created_at);
