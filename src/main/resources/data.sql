-- =============================================================================
-- Initial Reference Data: Currencies & Seed Users
-- =============================================================================

-- 1. Currencies (Minor units decimal places)
INSERT INTO currencies (code, decimal_places, name) VALUES ('USD', 2, 'US Dollar');
INSERT INTO currencies (code, decimal_places, name) VALUES ('EUR', 2, 'Euro');
INSERT INTO currencies (code, decimal_places, name) VALUES ('GBP', 2, 'British Pound');
INSERT INTO currencies (code, decimal_places, name) VALUES ('INR', 2, 'Indian Rupee');

-- 2. Seed Users (Balance in minor units, e.g. cents / paise)
INSERT INTO users (user_code, name, email, balance, version, created_at, updated_at) 
VALUES ('USR_ALICE', 'Alice Smith', 'alice@paymentsystem.io', 2500000, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (user_code, name, email, balance, version, created_at, updated_at) 
VALUES ('USR_BOB', 'Bob Jones', 'bob@paymentsystem.io', 1500000, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (user_code, name, email, balance, version, created_at, updated_at) 
VALUES ('USR_CHARLIE', 'Charlie Brown', 'charlie@paymentsystem.io', 5000000, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (user_code, name, email, balance, version, created_at, updated_at) 
VALUES ('USR_DAVID', 'David Miller', 'david@paymentsystem.io', 1000000, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 3. Seed API Keys (Customer Authentication)
INSERT INTO api_keys (key_value, customer_id, customer_name, active, created_at) VALUES ('apexpay_admin_secret_key_2026', 'CUST_ADMIN', 'ApexPay Admin Customer', true, CURRENT_TIMESTAMP);
INSERT INTO api_keys (key_value, customer_id, customer_name, active, created_at) VALUES ('test_customer_key_1', 'CUST_001', 'Acme Corp', true, CURRENT_TIMESTAMP);
INSERT INTO api_keys (key_value, customer_id, customer_name, active, created_at) VALUES ('test_customer_key_2', 'CUST_002', 'Beta Inc', true, CURRENT_TIMESTAMP);
INSERT INTO api_keys (key_value, customer_id, customer_name, active, created_at) VALUES ('test_key', 'CUST_TEST', 'Test Suite Account', true, CURRENT_TIMESTAMP);


