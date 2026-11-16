# Safe Production Changes: Zero-Downtime Database Migration Runbook

**System**: ApexPay — Distributed Fault-Tolerant Payment Engine  
**Standard**: Expand and Contract (Parallel Run) Migration Pattern  
**Tooling**: Flyway Database Migrations + Application Feature Flags  

---

## 1. Core Principles of Zero-Downtime Database Changes

In high-throughput financial and transaction engines, database schema changes must never require downtime, lock tables against concurrent transactions, or break backward compatibility with running application nodes.

### The Zero-Downtime Cardinal Rules:
1. **Never Make Breaking Schema Changes in One Step**: A change requiring database schema mutations, application code updates, and historical data transformation must be decomposed into sequential, independently deployable phases.
2. **Backward Compatibility First (Expand)**: Old application instances (Version $N$) must continue reading and writing successfully while new database columns or tables are introduced.
3. **Chunked Online Backfilling**: Historical rows must be populated in small, bounded transaction batches without acquiring table-level exclusive locks or exhausting replication lag buffers.
4. **Contract After Verification**: Strict database constraints (such as `NOT NULL`, foreign keys, or dropped deprecated columns) are only applied after all application nodes are running the new code and 100% of historical data has been backfilled.
5. **Feature Flag Guardrails**: Any new business capability (e.g., fee computation, double-entry ledger recording) must be guarded by dynamic feature flags, allowing instantaneous zero-downtime rollback without database reversions.

---

## 2. Real Production Example: Adding `fee_amount` to `payments`

### Problem Statement
The payment processing engine needs to record processing fee amounts (`fee_amount` in minor units, e.g., cents/paise as `BIGINT`) directly on the `payments` table.

- **Target State**: `fee_amount BIGINT NOT NULL DEFAULT 0`.
- **Naive (Dangerous) Approach**:
  ```sql
  -- DANGEROUS IN PRODUCTION:
  ALTER TABLE payments ADD COLUMN fee_amount BIGINT NOT NULL DEFAULT 0;
  ```
  **Why this causes an outage:**
  - On large tables (millions of rows), this command acquires an exclusive table lock (`ACCESS EXCLUSIVE` in PostgreSQL), blocking concurrent payment inserts and causing gateway timeout cascades.
  - Running instances of Version $N$ (which do not supply `fee_amount` in their insert statements) fail with `null value in column "fee_amount" violates not-null constraint` if the default is omitted or mishandled.
  - Rollback is non-trivial if application errors occur after deployment.

---

### Step-by-Step Safe Two-Step (Expand/Contract) Runbook

```
Phase 1: EXPAND                 Phase 2: DUAL WRITE            Phase 3: BACKFILL               Phase 4: CONTRACT
[Schema: Add Nullable Column] -> [App: Write New Column,      -> [DB: Online Batch Backfill]  -> [DB: Set NOT NULL]
                                  Read with Fallback]                                           [App: Remove Fallback]
```

---

### Step 1: Expand Phase (Flyway Migration `V3__add_fee_amount_to_payments.sql`)

Deploy the database migration **before** deploying new application code. The column is added as nullable with no strict constraints:

```sql
-- Flyway Migration: V3__add_fee_amount_to_payments.sql
-- Step 1: Add new column as nullable to preserve backward compatibility with running app instances

ALTER TABLE payments 
ADD COLUMN IF NOT EXISTS fee_amount BIGINT DEFAULT NULL;

-- Optional: Create index concurrently if queried directly
-- In PostgreSQL: CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payments_fee ON payments(fee_amount);
CREATE INDEX IF NOT EXISTS idx_payments_fee_amount ON payments(fee_amount);
```

#### Verification:
- Old application Version $N$ continues to process payments without interruption. It simply ignores the new `fee_amount` column.
- Database query verifies column presence:
  ```sql
  SELECT column_name, data_type, is_nullable 
  FROM information_schema.columns 
  WHERE table_name = 'payments' AND column_name = 'fee_amount';
  ```

---

### Step 2: Deploy Dual-Writing Application Version ($N+1$) with Feature Flag

Deploy Version $N+1$ of the application. The code writes the new column while providing a backward-compatible fallback when reading:

#### 1. Entity Mapping (`PaymentTransaction.java`):
```java
@Column(name = "fee_amount")
private Long feeAmount;

// Safe accessor with fallback for un-backfilled legacy rows
public Long getFeeAmount() {
    return feeAmount != null ? feeAmount : 0L;
}

public void setFeeAmount(Long feeAmount) {
    this.feeAmount = feeAmount;
}
```

#### 2. Service Layer Dual Writing with Feature Flag:
```java
@Value("${app.features.fee-calculation.enabled:true}")
private boolean feeCalculationEnabled;

public PaymentTransaction recordPayment(InitiatePaymentRequest request) {
    PaymentTransaction tx = new PaymentTransaction(...);
    
    if (feeCalculationEnabled) {
        long fee = calculateProcessingFee(request.getAmount(), request.getPaymentMethod());
        tx.setFeeAmount(fee);
    } else {
        tx.setFeeAmount(0L);
    }
    
    return paymentRepository.saveAndFlush(tx);
}
```

#### 3. Verification:
- New payments inserted by Version $N+1$ have populated `fee_amount` values.
- Historical payments have `fee_amount = NULL`, but `getFeeAmount()` safely returns `0L`.

---

### Step 3: Online Chunked Backfill of Historical Rows

Backfill legacy rows where `fee_amount IS NULL` using a non-blocking, chunked batch script. Never run an unbounded `UPDATE payments SET fee_amount = 0` in production!

#### Production Backfill Script (`scripts/backfill_payments_fee_amount.sql`):
```sql
DO $$
DECLARE
    batch_size INT := 5000;
    rows_updated INT := 1;
    total_updated INT := 0;
BEGIN
    WHILE rows_updated > 0 LOOP
        WITH batch AS (
            SELECT id 
            FROM payments 
            WHERE fee_amount IS NULL 
            LIMIT batch_size
            FOR UPDATE SKIP LOCKED
        )
        UPDATE payments p
        SET fee_amount = 0
        FROM batch
        WHERE p.id = batch.id;

        GET DIAGNOSTICS rows_updated = ROW_COUNT;
        total_updated := total_updated + rows_updated;
        
        -- Pause for 100ms to allow concurrent traffic and prevent replication lag
        PERFORM pg_sleep(0.1);
        COMMIT;
    END LOOP;
    
    RAISE NOTICE 'Backfill complete. Total rows updated: %', total_updated;
END $$;
```

#### Verification:
```sql
-- Must return 0 rows
SELECT COUNT(*) FROM payments WHERE fee_amount IS NULL;
```

---

### Step 4: Contract Phase (Flyway Migration `V4__enforce_fee_amount_not_null.sql`)

Once 100% of rows are populated and all instances are running Version $N+1$, apply the contract migration to harden database constraints:

```sql
-- Flyway Migration: V4__enforce_fee_amount_not_null.sql
-- Step 4: Enforce NOT NULL constraint and default after all rows are guaranteed non-null

ALTER TABLE payments 
ALTER COLUMN fee_amount SET DEFAULT 0;

ALTER TABLE payments 
ALTER COLUMN fee_amount SET NOT NULL;
```

#### Code Cleanup (Version $N+2$):
- Remove the temporary fallback logic in `getFeeAmount()`.
- Decommission the feature flag `app.features.fee-calculation.enabled`.

---

## 3. Rollback Runbook for Each Phase

| Failure Scenario | Active Phase | Immediate Recovery Action | Data Loss Risk |
| :--- | :---: | :--- | :---: |
| Bug in fee calculation logic | Phase 2 | Toggle feature flag: `app.features.fee-calculation.enabled=false`. No deploy required. | **Zero** |
| Deployment failure of Version $N+1$ | Phase 2 | Rollback container to Version $N$. Version $N$ ignores the nullable `fee_amount` column. | **Zero** |
| Backfill script causes lock contention | Phase 3 | Abort backfill script. Transactions release row locks immediately. Tune `batch_size` or sleep interval. | **Zero** |
| Unexpected NULL values during Phase 4 | Phase 4 | Flyway migration fails safely before applying `NOT NULL`. Fix remaining null rows and retry. | **Zero** |

---

## 4. Production Release Checklist

- [ ] **Flyway Validation**: Migrations tested locally on H2 and in CI on real PostgreSQL Testcontainers (`mvn test -Ppostgres-it`).
- [ ] **Index Concurrency**: All new indexes on existing high-volume tables created with `CONCURRENTLY`.
- [ ] **Zero Table Locks**: DDL operations verified not to require table rewrites or persistent locks.
- [ ] **Dual-Writing Verified**: Application correctly handles both populated and legacy fallback values.
- [ ] **Backfill Idempotency**: Backfill script is safe to run multiple times without duplicating or corrupting data.
- [ ] **Feature Flag Verified**: Feature flag toggles capability on/off with verified test coverage.
