package com.paymentsystem.integration;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.ReconciliationResult;
import com.paymentsystem.dto.response.SystemMetricsResponse;
import com.paymentsystem.ledger.LedgerEntry;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.ledger.LedgerEntryRepository;
import com.paymentsystem.ledger.LedgerReconciliationService;
import com.paymentsystem.service.MetricsService;
import com.paymentsystem.payment.PaymentProcessingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.ledger.enabled=false"
})
public class LedgerFeatureFlagIntegrationTest {

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Feature Flag: when app.ledger.enabled=false, payments process successfully without writing ledger entries")
    void testPaymentProcessing_WhenLedgerDisabled_DoesNotWriteLedgerEntries() {
        String userCode = "USR_ALICE";
        long amount = 5000L;
        String paymentId = "PAY_FLAG_OFF_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "IDEM_FLAG_OFF_" + UUID.randomUUID().toString().substring(0, 8);

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey,
                userCode,
                amount,
                "USD",
                "CREDIT_CARD",
                "Payment with ledger feature flag disabled"
        );

        try {
            PaymentResponse response = paymentProcessingService.initiatePayment(req);
            assertEquals(PaymentStatus.SUCCESS, response.getStatus());

            // Verify zero ledger entries were written for this transaction
            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.getPaymentId());
            assertTrue(entries.isEmpty(), "Zero ledger entries should be written when app.ledger.enabled=false");

            // Reconciliation should report DISABLED
            ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
            assertTrue(result.isBalanced());
            assertEquals("DISABLED", reconciliationService.getReconciliationStatus());

            // Metrics endpoint exposes ledgerEnabled = false
            SystemMetricsResponse metrics = metricsService.getMetrics(0);
            assertFalse(metrics.isLedgerEnabled());
            assertEquals("DISABLED", metrics.getReconciliationStatus());
        } finally {
            // Restore user balance and clean up test payment so subsequent tests running with ledger enabled remain balanced
            jdbcTemplate.update("UPDATE users SET balance = balance + ? WHERE user_code = ?", amount, userCode);
            jdbcTemplate.update("DELETE FROM payments WHERE idempotency_key = ?", idempotencyKey);
            jdbcTemplate.update("DELETE FROM idempotency_keys WHERE key_value = ?", idempotencyKey);
        }
    }
}
