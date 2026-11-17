package com.paymentsystem.ledger;

import com.paymentsystem.dto.response.PagedResponse;
import com.paymentsystem.dto.response.ReconciliationResult;
import com.paymentsystem.ledger.LedgerEntry;
import com.paymentsystem.ledger.LedgerEntryRepository;
import com.paymentsystem.ledger.LedgerReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Double-Entry Ledger & Reconciliation", description = "Endpoints for financial ledger records and automated reconciliation audits")
public class LedgerReconciliationController {

    private final LedgerReconciliationService reconciliationService;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    public LedgerReconciliationController(LedgerReconciliationService reconciliationService,
                                          LedgerEntryRepository ledgerEntryRepository) {
        this.reconciliationService = reconciliationService;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @PostMapping("/reconciliation/run")
    @Operation(summary = "Run Reconciliation Audit", description = "Executes an immediate double-entry ledger audit verifying debits=credits per currency and user balance=sum(ledger)")
    public ResponseEntity<ReconciliationResult> runReconciliation(
            @RequestParam(defaultValue = "0") long cutoffSeconds) {
        Duration cutoff = (cutoffSeconds > 0) ? Duration.ofSeconds(cutoffSeconds) : Duration.ZERO;
        ReconciliationResult result = reconciliationService.runReconciliation(cutoff);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/reconciliation/status")
    @Operation(summary = "Get Reconciliation Status", description = "Returns the latest reconciliation status, mismatch count, and execution timestamp")
    public ResponseEntity<Map<String, Object>> getReconciliationStatus() {
        return ResponseEntity.ok(Map.of(
                "status", reconciliationService.getReconciliationStatus(),
                "mismatchCount", reconciliationService.getMismatchCount(),
                "lastRunTime", reconciliationService.getLastRunTime() != null ? reconciliationService.getLastRunTime().toString() : "NEVER_RUN"
        ));
    }

    @GetMapping("/ledger/entries")
    @Operation(summary = "List Ledger Entries", description = "Query immutable double-entry ledger entries by transactionId or accountId")
    public ResponseEntity<List<LedgerEntry>> getLedgerEntries(
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String accountId) {
        if (transactionId != null && !transactionId.isBlank()) {
            return ResponseEntity.ok(ledgerEntryRepository.findByTransactionId(transactionId));
        } else if (accountId != null && !accountId.isBlank()) {
            return ResponseEntity.ok(ledgerEntryRepository.findByAccountId(accountId));
        } else {
            return ResponseEntity.ok(ledgerEntryRepository.findAll());
        }
    }
}
