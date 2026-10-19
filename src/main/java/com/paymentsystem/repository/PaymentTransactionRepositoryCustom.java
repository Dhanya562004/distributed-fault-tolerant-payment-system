package com.paymentsystem.repository;

import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;

import java.time.Instant;
import java.util.List;

/**
 * Custom repository interface for keyset-based cursor pagination of payment transactions.
 */
public interface PaymentTransactionRepositoryCustom {

    /**
     * Executes an indexed, OFFSET-free keyset pagination query with composite ordering
     * (createdAt DESC, id DESC).
     *
     * @param status optional status filter
     * @param fromDate optional lower bound (inclusive) for createdAt
     * @param toDate optional upper bound (inclusive) for createdAt
     * @param cursorCreatedAt timestamp of last seen transaction (null for first page)
     * @param cursorId database primary key ID of last seen transaction (null for first page)
     * @param limit maximum records to fetch (caller passes limit + 1)
     * @return list of matching transactions up to limit rows
     */
    List<PaymentTransaction> findTransactionsKeyset(
            PaymentStatus status,
            Instant fromDate,
            Instant toDate,
            Instant cursorCreatedAt,
            Long cursorId,
            int limit
    );
}
