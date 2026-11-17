package com.paymentsystem.ledger;

import com.paymentsystem.ledger.LedgerEntry;

import java.util.List;

public interface LedgerService {

    /**
     * Records matching DEBIT and CREDIT double-entry ledger rows for an atomic payment.
     * DEBIT: Customer Account (reduces deposit balance)
     * CREDIT: SYSTEM_SETTLEMENT Account (increases platform clearing)
     */
    List<LedgerEntry> recordPaymentEntries(String paymentId, String userId, long amount, String currency, String description);

    /**
     * Records matching DEBIT and CREDIT double-entry ledger rows for an atomic refund.
     * DEBIT: SYSTEM_SETTLEMENT Account (decreases platform clearing)
     * CREDIT: Customer Account (restores deposit balance)
     */
    List<LedgerEntry> recordRefundEntries(String refundId, String userId, long amount, String currency, String description);

    /**
     * Records matching DEBIT and CREDIT opening-balance ledger entries for a user account.
     * DEBIT: SYSTEM_RESERVE Account (initial equity reserve allocation)
     * CREDIT: Customer Account (initial deposit balance)
     */
    List<LedgerEntry> recordOpeningBalance(String userCode, long amount, String currency);

    /**
     * Retrieves all ledger entries for a given financial transaction ID.
     */
    List<LedgerEntry> getEntriesByTransactionId(String transactionId);

    /**
     * Retrieves all ledger entries for a given account.
     */
    List<LedgerEntry> getEntriesByAccountId(String accountId);
}
