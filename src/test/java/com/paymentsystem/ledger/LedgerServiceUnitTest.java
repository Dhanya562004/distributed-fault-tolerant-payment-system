package com.paymentsystem.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService Unit Tests")
class LedgerServiceUnitTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private Clock fixedClock;
    private LedgerServiceImpl ledgerService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneId.of("UTC"));
        ledgerService = new LedgerServiceImpl(ledgerEntryRepository, fixedClock);
    }

    @Test
    @DisplayName("recordPaymentEntries with standard and fallback values")
    void testRecordPaymentEntries_StandardAndFallbacks() {
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Test with explicit currency and description
        List<LedgerEntry> entries1 = ledgerService.recordPaymentEntries("PAY_1", "USR_ALICE", 5000L, "EUR", "Test payment");
        assertEquals(2, entries1.size());
        assertEquals(EntrySide.DEBIT, entries1.get(0).getEntrySide());
        assertEquals("USR_ALICE", entries1.get(0).getAccountId());
        assertEquals(5000L, entries1.get(0).getAmount());
        assertEquals("EUR", entries1.get(0).getCurrency());
        assertEquals("Test payment", entries1.get(0).getDescription());

        assertEquals(EntrySide.CREDIT, entries1.get(1).getEntrySide());
        assertEquals(LedgerServiceImpl.ACCOUNT_SYSTEM_SETTLEMENT, entries1.get(1).getAccountId());
        assertEquals(5000L, entries1.get(1).getAmount());

        // Test with null currency and null description (triggering default fallbacks)
        List<LedgerEntry> entries2 = ledgerService.recordPaymentEntries("PAY_2", "USR_BOB", 1000L, null, null);
        assertEquals(2, entries2.size());
        assertEquals("USD", entries2.get(0).getCurrency());
        assertEquals("Payment debit for PAY_2", entries2.get(0).getDescription());

        // Test with blank currency
        List<LedgerEntry> entries3 = ledgerService.recordPaymentEntries("PAY_3", "USR_CHARLIE", 2000L, "   ", "desc");
        assertEquals("USD", entries3.get(0).getCurrency());
    }

    @Test
    @DisplayName("recordRefundEntries with standard and fallback values")
    void testRecordRefundEntries_StandardAndFallbacks() {
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Standard refund entries
        List<LedgerEntry> entries1 = ledgerService.recordRefundEntries("REF_1", "USR_ALICE", 2000L, "GBP", "Refund test");
        assertEquals(2, entries1.size());
        assertEquals(EntrySide.DEBIT, entries1.get(0).getEntrySide());
        assertEquals(LedgerServiceImpl.ACCOUNT_SYSTEM_SETTLEMENT, entries1.get(0).getAccountId());
        assertEquals(2000L, entries1.get(0).getAmount());
        assertEquals("GBP", entries1.get(0).getCurrency());

        assertEquals(EntrySide.CREDIT, entries1.get(1).getEntrySide());
        assertEquals("USR_ALICE", entries1.get(1).getAccountId());
        assertEquals(2000L, entries1.get(1).getAmount());
        assertEquals("Refund test", entries1.get(1).getDescription());

        // Null currency and null description fallbacks
        List<LedgerEntry> entries2 = ledgerService.recordRefundEntries("REF_2", "USR_BOB", 500L, null, null);
        assertEquals(2, entries2.size());
        assertEquals("USD", entries2.get(0).getCurrency());
        assertEquals("Refund credit for REF_2", entries2.get(1).getDescription());

        // Blank currency fallback
        List<LedgerEntry> entries3 = ledgerService.recordRefundEntries("REF_3", "USR_CHARLIE", 800L, "  ", "desc");
        assertEquals("USD", entries3.get(0).getCurrency());
    }

    @Test
    @DisplayName("recordOpeningBalance branches: amount <= 0, already exists, and fresh insert")
    void testRecordOpeningBalance_AllBranches() {
        // 1. Amount <= 0 returns empty list
        List<LedgerEntry> zeroEntries = ledgerService.recordOpeningBalance("USR_ZERO", 0L, "USD");
        assertTrue(zeroEntries.isEmpty());
        List<LedgerEntry> negEntries = ledgerService.recordOpeningBalance("USR_NEG", -500L, "USD");
        assertTrue(negEntries.isEmpty());
        verify(ledgerEntryRepository, never()).save(any());

        // 2. Already exists returns existing entries
        String txId = "OB_USR_EXISTING";
        when(ledgerEntryRepository.existsByTransactionIdAndEntrySide(txId, EntrySide.CREDIT)).thenReturn(true);
        LedgerEntry existingEntry = new LedgerEntry("LED_1", txId, LedgerEntryType.OPENING_BALANCE, "USR_EXISTING", EntrySide.CREDIT, 10000L, "USD", "desc", Instant.now());
        when(ledgerEntryRepository.findByTransactionId(txId)).thenReturn(List.of(existingEntry));

        List<LedgerEntry> existingResult = ledgerService.recordOpeningBalance("USR_EXISTING", 10000L, "USD");
        assertEquals(1, existingResult.size());
        assertEquals("LED_1", existingResult.get(0).getEntryId());

        // 3. Fresh insert with null currency fallback
        String newTxId = "OB_USR_NEW";
        when(ledgerEntryRepository.existsByTransactionIdAndEntrySide(newTxId, EntrySide.CREDIT)).thenReturn(false);
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<LedgerEntry> newEntries = ledgerService.recordOpeningBalance("USR_NEW", 50000L, null);
        assertEquals(2, newEntries.size());
        assertEquals(EntrySide.DEBIT, newEntries.get(0).getEntrySide());
        assertEquals(LedgerServiceImpl.ACCOUNT_SYSTEM_RESERVE, newEntries.get(0).getAccountId());
        assertEquals("USD", newEntries.get(0).getCurrency());

        assertEquals(EntrySide.CREDIT, newEntries.get(1).getEntrySide());
        assertEquals("USR_NEW", newEntries.get(1).getAccountId());
        assertEquals(50000L, newEntries.get(1).getAmount());
    }

    @Test
    @DisplayName("getEntriesByTransactionId and getEntriesByAccountId delegate to repository")
    void testGetEntriesQueries() {
        LedgerEntry entry = new LedgerEntry("LED_1", "TX_1", LedgerEntryType.PAYMENT, "USR_1", EntrySide.DEBIT, 1000L, "USD", "test", Instant.now());
        when(ledgerEntryRepository.findByTransactionId("TX_1")).thenReturn(List.of(entry));
        when(ledgerEntryRepository.findByAccountId("USR_1")).thenReturn(List.of(entry));

        List<LedgerEntry> byTx = ledgerService.getEntriesByTransactionId("TX_1");
        assertEquals(1, byTx.size());
        List<LedgerEntry> byAcc = ledgerService.getEntriesByAccountId("USR_1");
        assertEquals(1, byAcc.size());
    }

    @Test
    @DisplayName("Single-arg constructor initializes with system clock")
    void testSingleArgConstructor() {
        LedgerServiceImpl service = new LedgerServiceImpl(ledgerEntryRepository);
        assertNotNull(service);
    }
}
