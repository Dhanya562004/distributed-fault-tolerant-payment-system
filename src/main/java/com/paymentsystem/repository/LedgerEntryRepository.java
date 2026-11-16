package com.paymentsystem.repository;

import com.paymentsystem.entity.EntrySide;
import com.paymentsystem.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(String transactionId);

    List<LedgerEntry> findByAccountId(String accountId);

    boolean existsByTransactionId(String transactionId);

    boolean existsByTransactionIdAndEntrySide(String transactionId, EntrySide entrySide);

    long countByTransactionId(String transactionId);

    @Override
    default void delete(LedgerEntry entity) {
        throw new UnsupportedOperationException("Ledger is append-only: deletions are strictly prohibited");
    }

    @Override
    default void deleteById(Long id) {
        throw new UnsupportedOperationException("Ledger is append-only: deletions are strictly prohibited");
    }

    @Override
    default void deleteAll() {
        throw new UnsupportedOperationException("Ledger is append-only: deletions are strictly prohibited");
    }
}
