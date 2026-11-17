package com.paymentsystem.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LedgerEntry and Repository Append-Only Unit Tests")
class LedgerEntryEntityTest {

    @Test
    @DisplayName("LedgerEntry constructor and getters")
    void testLedgerEntryConstructorAndGetters() {
        Instant now = Instant.now();
        LedgerEntry entry = new LedgerEntry("LED_1", "TX_1", LedgerEntryType.PAYMENT, "USR_1", EntrySide.DEBIT, 5000L, "USD", "test", now);

        assertEquals("LED_1", entry.getEntryId());
        assertEquals("TX_1", entry.getTransactionId());
        assertEquals(LedgerEntryType.PAYMENT, entry.getEntryType());
        assertEquals("USR_1", entry.getAccountId());
        assertEquals(EntrySide.DEBIT, entry.getEntrySide());
        assertEquals(5000L, entry.getAmount());
        assertEquals("USD", entry.getCurrency());
        assertEquals("test", entry.getDescription());
        assertEquals(now, entry.getCreatedAt());

        LedgerEntry emptyEntry = new LedgerEntry();
        assertNull(emptyEntry.getEntryId());
        assertNull(emptyEntry.getId());

        // Equals and HashCode
        LedgerEntry same = new LedgerEntry("LED_1", "TX_1", LedgerEntryType.PAYMENT, "USR_1", EntrySide.DEBIT, 5000L, "USD", "test", now);
        LedgerEntry diff = new LedgerEntry("LED_2", "TX_1", LedgerEntryType.PAYMENT, "USR_1", EntrySide.DEBIT, 5000L, "USD", "test", now);
        assertEquals(entry, entry);
        assertEquals(entry, same);
        assertNotEquals(entry, diff);
        assertNotEquals(entry, null);
        assertNotEquals(entry, "some string");
        assertEquals(entry.hashCode(), same.hashCode());
        assertNotNull(entry.toString());

        // PreUpdate and PreRemove
        assertThrows(UnsupportedOperationException.class, entry::onPreUpdate);
        assertThrows(UnsupportedOperationException.class, entry::onPreRemove);

        // Validation branches: null amount, non-positive amount
        assertThrows(IllegalArgumentException.class, () ->
                new LedgerEntry("L", "T", LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, null, "USD", "desc", now));
        assertThrows(IllegalArgumentException.class, () ->
                new LedgerEntry("L", "T", LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, 0L, "USD", "desc", now));
        assertThrows(IllegalArgumentException.class, () ->
                new LedgerEntry("L", "T", LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, -5L, "USD", "desc", now));

        // Validation branches: requireNonNull
        assertThrows(NullPointerException.class, () ->
                new LedgerEntry(null, "T", LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, 100L, "USD", "desc", now));
        assertThrows(NullPointerException.class, () ->
                new LedgerEntry("L", null, LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, 100L, "USD", "desc", now));
        assertThrows(NullPointerException.class, () ->
                new LedgerEntry("L", "T", null, "A", EntrySide.DEBIT, 100L, "USD", "desc", now));
        assertThrows(NullPointerException.class, () ->
                new LedgerEntry("L", "T", LedgerEntryType.PAYMENT, null, EntrySide.DEBIT, 100L, "USD", "desc", now));
        assertThrows(NullPointerException.class, () ->
                new LedgerEntry("L", "T", LedgerEntryType.PAYMENT, "A", null, 100L, "USD", "desc", now));

        // Default currency and createdAt
        LedgerEntry defaults = new LedgerEntry("L_DEF", "T", LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, 100L, null, "desc", null);
        assertEquals("USD", defaults.getCurrency());
        assertNotNull(defaults.getCreatedAt());

        LedgerEntry blankCurr = new LedgerEntry("L_BLANK", "T", LedgerEntryType.PAYMENT, "A", EntrySide.DEBIT, 100L, "   ", "desc", null);
        assertEquals("USD", blankCurr.getCurrency());
    }

    @Test
    @DisplayName("LedgerEntryRepository append-only default methods throw UnsupportedOperationException")
    void testLedgerEntryRepositoryAppendOnlyProtection() {
        LedgerEntryRepository repo = new LedgerEntryRepository() {
            @Override public <S extends LedgerEntry> S save(S entity) { return entity; }
            @Override public <S extends LedgerEntry> java.util.List<S> saveAll(Iterable<S> entities) { return java.util.List.of(); }
            @Override public java.util.Optional<LedgerEntry> findById(Long aLong) { return java.util.Optional.empty(); }
            @Override public boolean existsById(Long aLong) { return false; }
            @Override public java.util.List<LedgerEntry> findAll() { return java.util.List.of(); }
            @Override public java.util.List<LedgerEntry> findAllById(Iterable<Long> longs) { return java.util.List.of(); }
            @Override public long count() { return 0; }
            @Override public void flush() {}
            @Override public <S extends LedgerEntry> S saveAndFlush(S entity) { return entity; }
            @Override public <S extends LedgerEntry> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { return java.util.List.of(); }
            @Override public void deleteAllInBatch(Iterable<LedgerEntry> entities) {}
            @Override public void deleteAllByIdInBatch(Iterable<Long> longs) {}
            @Override public void deleteAllInBatch() {}
            @Override public LedgerEntry getOne(Long aLong) { return null; }
            @Override public LedgerEntry getById(Long aLong) { return null; }
            @Override public LedgerEntry getReferenceById(Long aLong) { return null; }
            @Override public <S extends LedgerEntry> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return java.util.Optional.empty(); }
            @Override public <S extends LedgerEntry> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { return java.util.List.of(); }
            @Override public <S extends LedgerEntry> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
            @Override public <S extends LedgerEntry> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return null; }
            @Override public <S extends LedgerEntry> long count(org.springframework.data.domain.Example<S> example) { return 0; }
            @Override public <S extends LedgerEntry> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
            @Override public <S extends LedgerEntry, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
            @Override public java.util.List<LedgerEntry> findAll(org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
            @Override public org.springframework.data.domain.Page<LedgerEntry> findAll(org.springframework.data.domain.Pageable pageable) { return null; }
            @Override public void deleteAllById(Iterable<? extends Long> longs) {}
            @Override public void deleteAll(Iterable<? extends LedgerEntry> entities) {}
            @Override public java.util.List<LedgerEntry> findByTransactionId(String transactionId) { return java.util.List.of(); }
            @Override public java.util.List<LedgerEntry> findByAccountId(String accountId) { return java.util.List.of(); }
            @Override public boolean existsByTransactionId(String transactionId) { return false; }
            @Override public boolean existsByTransactionIdAndEntrySide(String transactionId, EntrySide entrySide) { return false; }
            @Override public long countByTransactionId(String transactionId) { return 0; }
        };

        LedgerEntry entry = new LedgerEntry("LED_1", "TX_1", LedgerEntryType.PAYMENT, "USR_1", EntrySide.DEBIT, 100L, "USD", "desc", Instant.now());

        assertThrows(UnsupportedOperationException.class, () -> repo.delete(entry));
        assertThrows(UnsupportedOperationException.class, () -> repo.deleteById(1L));
        assertThrows(UnsupportedOperationException.class, () -> repo.deleteAll());
    }
}
