package com.paymentsystem.ledger;

import com.paymentsystem.dto.response.ReconciliationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerReconciliationController Unit Tests")
class LedgerReconciliationControllerTest {

    @Mock
    private LedgerReconciliationService reconciliationService;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @InjectMocks
    private LedgerReconciliationController controller;

    @Test
    @DisplayName("runReconciliation with positive cutoffSeconds and zero cutoffSeconds")
    void testRunReconciliation() {
        ReconciliationResult balanced = new ReconciliationResult(Instant.now(), true, 0L, List.of(), List.of());

        when(reconciliationService.runReconciliation(Duration.ofSeconds(30))).thenReturn(balanced);
        ResponseEntity<ReconciliationResult> res1 = controller.runReconciliation(30);
        assertEquals(200, res1.getStatusCode().value());
        assertTrue(res1.getBody().isBalanced());

        when(reconciliationService.runReconciliation(Duration.ZERO)).thenReturn(balanced);
        ResponseEntity<ReconciliationResult> res2 = controller.runReconciliation(0);
        assertEquals(200, res2.getStatusCode().value());
        assertTrue(res2.getBody().isBalanced());
    }

    @Test
    @DisplayName("getReconciliationStatus when lastRunTime is present and null")
    void testGetReconciliationStatus() {
        Instant now = Instant.now();
        when(reconciliationService.getReconciliationStatus()).thenReturn("BALANCED");
        when(reconciliationService.getMismatchCount()).thenReturn(0L);
        when(reconciliationService.getLastRunTime()).thenReturn(now);

        ResponseEntity<Map<String, Object>> res1 = controller.getReconciliationStatus();
        assertEquals(200, res1.getStatusCode().value());
        assertEquals("BALANCED", res1.getBody().get("status"));
        assertEquals(now.toString(), res1.getBody().get("lastRunTime"));

        when(reconciliationService.getLastRunTime()).thenReturn(null);
        ResponseEntity<Map<String, Object>> res2 = controller.getReconciliationStatus();
        assertEquals(200, res2.getStatusCode().value());
        assertEquals("NEVER_RUN", res2.getBody().get("lastRunTime"));
    }

    @Test
    @DisplayName("getLedgerEntries by transactionId, by accountId, and findAll")
    void testGetLedgerEntries() {
        LedgerEntry entry = new LedgerEntry("LED_1", "TX_1", LedgerEntryType.PAYMENT, "ACC_1", EntrySide.DEBIT, 100L, "USD", "desc", Instant.now());

        // By transactionId
        when(ledgerEntryRepository.findByTransactionId("TX_1")).thenReturn(List.of(entry));
        ResponseEntity<List<LedgerEntry>> res1 = controller.getLedgerEntries("TX_1", null);
        assertEquals(1, res1.getBody().size());

        // By accountId
        when(ledgerEntryRepository.findByAccountId("ACC_1")).thenReturn(List.of(entry));
        ResponseEntity<List<LedgerEntry>> res2 = controller.getLedgerEntries(null, "ACC_1");
        assertEquals(1, res2.getBody().size());

        // Blank params -> findAll
        when(ledgerEntryRepository.findAll()).thenReturn(List.of(entry));
        ResponseEntity<List<LedgerEntry>> res3 = controller.getLedgerEntries(null, null);
        assertEquals(1, res3.getBody().size());

        ResponseEntity<List<LedgerEntry>> res4 = controller.getLedgerEntries("  ", "  ");
        assertEquals(1, res4.getBody().size());
    }
}
