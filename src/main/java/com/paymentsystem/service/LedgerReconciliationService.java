package com.paymentsystem.service;

import com.paymentsystem.dto.response.ReconciliationResult;

import java.time.Duration;
import java.time.Instant;

public interface LedgerReconciliationService {

    /**
     * Executes double-entry ledger reconciliation using a default 2-second cutoff window
     * to prevent false alarms from in-flight payments.
     */
    ReconciliationResult runReconciliation();

    /**
     * Executes double-entry ledger reconciliation with a specific cutoff window.
     * Uses SQL SUMs directly in the database (no in-memory loops):
     * 1. Checks: total debits = total credits PER CURRENCY.
     * 2. Checks: each user's balance = sum(CREDIT) - sum(DEBIT) of their ledger rows.
     */
    ReconciliationResult runReconciliation(Duration cutoffDuration);

    /**
     * Current total mismatch count from the most recent reconciliation run.
     */
    long getMismatchCount();

    /**
     * Timestamp of the most recent reconciliation run.
     */
    Instant getLastRunTime();

    /**
     * Operational status ("BALANCED", "MISMATCH_DETECTED", or "INITIALIZING").
     */
    String getReconciliationStatus();
}
