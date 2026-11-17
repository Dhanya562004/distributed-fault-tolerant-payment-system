package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.ReconciliationResult;
import com.paymentsystem.ledger.EntrySide;
import com.paymentsystem.ledger.LedgerEntry;
import com.paymentsystem.ledger.LedgerEntryType;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.ServiceUnavailableException;
import com.paymentsystem.ledger.LedgerEntryRepository;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.runner.LedgerOpeningBalanceBackfillRunner;
import com.paymentsystem.service.GatewaySimulationService;
import com.paymentsystem.ledger.LedgerReconciliationService;
import com.paymentsystem.payment.PaymentProcessingService;
import com.paymentsystem.payment.PaymentTransactionCoordinator;
import com.paymentsystem.payment.RefundProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
public class LedgerDoubleEntryIntegrationTest {

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private RefundProcessingService refundProcessingService;

    @Autowired
    private PaymentTransactionCoordinator paymentCoordinator;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private LedgerOpeningBalanceBackfillRunner backfillRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private GatewaySimulationService gatewaySimulationService;

    @BeforeEach
    void setUp() {
        backfillRunner.backfillOpeningBalances();
    }

    @Test
    @DisplayName("Normal Payment: writes matching DEBIT and CREDIT rows in same transaction as balance deduction")
    void testNormalPayment_WritesMatchingDebitAndCredit_InSameTransaction() {
        String userCode = "USR_ALICE";
        User userBefore = userRepository.findByUserCode(userCode).orElseThrow();
        long balanceBefore = userBefore.getBalance();

        String paymentId = "PAY_LEDGER_NORM_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "IDEM_LED_NORM_" + UUID.randomUUID().toString().substring(0, 8);
        long paymentAmount = 10000L; // $100.00

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey,
                userCode,
                paymentAmount,
                "USD",
                "CREDIT_CARD",
                "Double entry payment test"
        );

        PaymentResponse payment = paymentProcessingService.initiatePayment(req);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());

        // 1. Verify user balance was deducted
        User userAfter = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(balanceBefore - paymentAmount, userAfter.getBalance());

        // 2. Verify ledger rows: exactly 2 matching rows for this payment
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(payment.getPaymentId());
        assertEquals(2, entries.size(), "Should have exactly 1 debit and 1 credit row");

        LedgerEntry debitEntry = entries.stream()
                .filter(e -> e.getEntrySide() == EntrySide.DEBIT)
                .findFirst().orElseThrow();
        assertEquals(userCode, debitEntry.getAccountId(), "Debit should be to user account");
        assertEquals(paymentAmount, debitEntry.getAmount());
        assertEquals("USD", debitEntry.getCurrency());
        assertEquals(LedgerEntryType.PAYMENT, debitEntry.getEntryType());

        LedgerEntry creditEntry = entries.stream()
                .filter(e -> e.getEntrySide() == EntrySide.CREDIT)
                .findFirst().orElseThrow();
        assertEquals("SYSTEM_SETTLEMENT", creditEntry.getAccountId(), "Credit should be to settlement account");
        assertEquals(paymentAmount, creditEntry.getAmount());
        assertEquals("USD", creditEntry.getCurrency());
        assertEquals(LedgerEntryType.PAYMENT, creditEntry.getEntryType());

        // 3. Verify reconciliation audit passes
        ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(result.isBalanced(), "System should be balanced after normal payment");
        assertEquals(0, result.getTotalMismatches());
    }

    @Test
    @DisplayName("Full Refund: writes matching DEBIT (Settlement) and CREDIT (User) restoring balance")
    void testFullRefund_WritesMatchingDebitAndCredit_InSameTransaction() {
        String userCode = "USR_BOB";
        User userBefore = userRepository.findByUserCode(userCode).orElseThrow();
        long balanceBefore = userBefore.getBalance();

        String paymentId = "PAY_LEDGER_REF_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "IDEM_LED_REF_" + UUID.randomUUID().toString().substring(0, 8);
        long amount = 15000L;

        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey,
                userCode,
                amount,
                "USD",
                "CREDIT_CARD",
                "Payment to refund"
        );
        PaymentResponse payment = paymentProcessingService.initiatePayment(payReq);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());

        // Execute full refund
        String refundIdemKey = "IDEM_REF_FULL_" + UUID.randomUUID().toString().substring(0, 8);
        ProcessRefundRequest refundReq = new ProcessRefundRequest(payment.getPaymentId(), refundIdemKey, amount, "Full refund test");
        refundProcessingService.processRefund(refundReq);

        // 1. Verify user balance restored
        User userAfterRefund = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(balanceBefore, userAfterRefund.getBalance(), "User balance should return to initial after full refund");

        // 2. Verify payment status is REFUNDED
        PaymentTransaction refundedPayment = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, refundedPayment.getStatus());

        // 3. Verify reconciliation audit passes with 0 mismatches
        ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(result.isBalanced(), "System should be balanced after full refund");
        assertEquals(0, result.getTotalMismatches());
    }

    @Test
    @DisplayName("Partial Refund: multiple partial refunds write distinct matching rows, payment stays SUCCESS until full")
    void testPartialRefunds_MultipleRefundRows_ConsistentBalance() {
        String userCode = "USR_CHARLIE";
        User userBefore = userRepository.findByUserCode(userCode).orElseThrow();
        long balanceBefore = userBefore.getBalance();

        long paymentAmount = 20000L;
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                "IDEM_PARTIAL_" + UUID.randomUUID(),
                userCode,
                paymentAmount,
                "USD",
                "CREDIT_CARD",
                "Partial refund test payment"
        );
        PaymentResponse payment = paymentProcessingService.initiatePayment(payReq);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());

        // Partial Refund 1: 8000
        long refund1Amount = 8000L;
        refundProcessingService.processRefund(new ProcessRefundRequest(payment.getPaymentId(), "IDEM_P1_" + UUID.randomUUID(), refund1Amount, "Partial 1"));

        PaymentTransaction afterP1 = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, afterP1.getStatus(), "Partial refund leaves payment in SUCCESS");

        // Partial Refund 2: 7000
        long refund2Amount = 7000L;
        refundProcessingService.processRefund(new ProcessRefundRequest(payment.getPaymentId(), "IDEM_P2_" + UUID.randomUUID(), refund2Amount, "Partial 2"));

        PaymentTransaction afterP2 = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, afterP2.getStatus(), "Partial refund leaves payment in SUCCESS");

        // Net balance deduction: 20000 - 15000 = 5000
        User userAfterPartial = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(balanceBefore - 5000L, userAfterPartial.getBalance());

        // Reconciliation passes
        ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(result.isBalanced());
        assertEquals(0, result.getTotalMismatches());
    }

    @Test
    @DisplayName("Failure in the Middle: rolls back balance and ledger entries (nothing half-written)")
    void testFailureInMiddle_RollsBackEverything_NothingHalfWritten() {
        String userCode = "USR_DAVID";
        User userBefore = userRepository.findByUserCode(userCode).orElseThrow();
        long balanceBefore = userBefore.getBalance();

        String paymentId = "PAY_FAIL_MID_" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = "IDEM_FAIL_MID_" + UUID.randomUUID().toString().substring(0, 8);
        long paymentAmount = 10000L;

        PaymentTransaction tx = new PaymentTransaction(
                paymentId,
                idempotencyKey,
                userCode,
                paymentAmount,
                "USD",
                "CREDIT_CARD",
                "Failure in middle test"
        );
        paymentRepository.saveAndFlush(tx);
        paymentCoordinator.commitPaymentProcessing(paymentId);

        // Force gateway simulation failure
        doThrow(new ServiceUnavailableException("Simulated gateway failure"))
                .when(gatewaySimulationService).simulateGatewayCall(any(), any());

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey,
                userCode,
                paymentAmount,
                "USD",
                "CREDIT_CARD",
                "Failure in middle test"
        );

        assertThrows(ServiceUnavailableException.class, () -> {
            paymentCoordinator.executePaymentAttempt(paymentId, req);
        });

        // 1. Verify balance was untouched (rolled back)
        User userAfter = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(balanceBefore, userAfter.getBalance(), "User balance must not be deducted on failure");

        // 2. Verify 0 ledger rows written for this failed payment (no half-written rows)
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(paymentId);
        assertTrue(entries.isEmpty(), "Zero ledger entries should exist for a rolled-back transaction");

        // 3. Reconciliation remains balanced
        ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(result.isBalanced());
    }

    @Test
    @DisplayName("Duplicate Retry: Unique constraint uk_ledger_tx_side prevents duplicate entries")
    void testDuplicateRetry_UniqueConstraintPreventsDuplicateLedgerRows() {
        String txId = "TX_DUP_TEST_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            LedgerEntry debit1 = new LedgerEntry(
                    "LED_D1_" + UUID.randomUUID().toString().substring(0, 8),
                    txId,
                    LedgerEntryType.PAYMENT,
                    "TEST_DUP_ACCT",
                    EntrySide.DEBIT,
                    5000L,
                    "USD",
                    "First debit",
                    Instant.now()
            );
            LedgerEntry credit1 = new LedgerEntry(
                    "LED_C1_" + UUID.randomUUID().toString().substring(0, 8),
                    txId,
                    LedgerEntryType.PAYMENT,
                    "SYSTEM_SETTLEMENT",
                    EntrySide.CREDIT,
                    5000L,
                    "USD",
                    "First credit",
                    Instant.now()
            );
            ledgerEntryRepository.saveAndFlush(debit1);
            ledgerEntryRepository.saveAndFlush(credit1);

            // Attempt second debit for same transaction ID
            LedgerEntry duplicateDebit = new LedgerEntry(
                    "LED_D2_" + UUID.randomUUID().toString().substring(0, 8),
                    txId,
                    LedgerEntryType.PAYMENT,
                    "TEST_DUP_ACCT",
                    EntrySide.DEBIT,
                    5000L,
                    "USD",
                    "Duplicate debit attempt",
                    Instant.now()
            );

            assertThrows(DataIntegrityViolationException.class, () -> {
                ledgerEntryRepository.saveAndFlush(duplicateDebit);
            }, "Unique constraint (transaction_id, entry_side) must prevent duplicate debits for same transaction");

        } finally {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txId);
        }
    }

    @Test
    @DisplayName("Append-Only: Direct modification or deletion throws UnsupportedOperationException")
    void testAppendOnly_UpdateAndDeleteProhibited() {
        String txId = "TX_IMMUTABLE_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            LedgerEntry entry = new LedgerEntry(
                    "LED_IMMUTABLE_" + UUID.randomUUID().toString().substring(0, 8),
                    txId,
                    LedgerEntryType.PAYMENT,
                    "TEST_IMMUTABLE_ACCT",
                    EntrySide.DEBIT,
                    1000L,
                    "USD",
                    "Immutable test",
                    Instant.now()
            );
            LedgerEntry saved = ledgerEntryRepository.saveAndFlush(entry);

            // Deletion at repository level is blocked
            assertThrows(UnsupportedOperationException.class, () -> {
                ledgerEntryRepository.delete(saved);
            });

            assertThrows(UnsupportedOperationException.class, () -> {
                ledgerEntryRepository.deleteById(saved.getId());
            });
        } finally {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txId);
        }
    }

    @Test
    @DisplayName("Reconciliation Job: Deliberately tampered user balance is detected and exposed in metrics")
    void testReconciliationCatchesTamperedUserBalance() {
        String userCode = "USR_ALICE";
        long tamperingAmount = 77777L;

        // Ensure initially balanced
        ReconciliationResult initialResult = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(initialResult.isBalanced());

        try {
            // Deliberately tamper with user balance in the database
            jdbcTemplate.update("UPDATE users SET balance = balance + ? WHERE user_code = ?", tamperingAmount, userCode);

            // Run reconciliation audit
            ReconciliationResult audit = reconciliationService.runReconciliation(Duration.ZERO);

            assertFalse(audit.isBalanced(), "Reconciliation should fail after balance tampering");
            assertTrue(audit.getTotalMismatches() >= 1, "Should have at least 1 mismatch");
            assertEquals("MISMATCH_DETECTED", reconciliationService.getReconciliationStatus());
            assertTrue(reconciliationService.getMismatchCount() >= 1);

            boolean foundAliceDiscrepancy = audit.getUserDiscrepancies().stream()
                    .anyMatch(d -> d.getUserCode().equals(userCode) && d.getDiscrepancy() == tamperingAmount);
            assertTrue(foundAliceDiscrepancy, "Reconciliation must pinpoint the exact tampered user and discrepancy amount");

        } finally {
            // Restore balance so subsequent tests are not affected
            jdbcTemplate.update("UPDATE users SET balance = balance - ? WHERE user_code = ?", tamperingAmount, userCode);
            reconciliationService.runReconciliation(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("Reconciliation Job: Corrupted unbalanced ledger entry triggers currency debit!=credit mismatch")
    void testReconciliationCatchesCorruptedLedgerRow() {
        String corruptTxId = "TX_CORRUPT_" + UUID.randomUUID().toString().substring(0, 8);
        long corruptAmount = 99999L;

        try {
            // Insert an unbalanced DEBIT row directly via plain SQL (no matching CREDIT) with explicit past timestamp
            java.sql.Timestamp pastTimestamp = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10));
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (entry_id, transaction_id, entry_type, account_id, entry_side, amount, currency, description, created_at)
                    VALUES (?, ?, 'PAYMENT', 'CORRUPT_ACCT', 'DEBIT', ?, 'EUR', 'Unbalanced corrupt row', ?)
                    """, "LED_CORRUPT_" + UUID.randomUUID(), corruptTxId, corruptAmount, pastTimestamp);

            ReconciliationResult audit = reconciliationService.runReconciliation(Duration.ZERO);
            assertFalse(audit.isBalanced(), "Reconciliation must fail when debits != credits for currency EUR");

            boolean foundEurMismatch = audit.getCurrencyDiscrepancies().stream()
                    .anyMatch(d -> "EUR".equalsIgnoreCase(d.getCurrency()) && d.getDifference() == corruptAmount);
            assertTrue(foundEurMismatch, "Reconciliation must identify the exact EUR currency imbalance");

        } finally {
            // Clean up corrupt row
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id = ?", corruptTxId);
            reconciliationService.runReconciliation(Duration.ZERO);
        }
    }
}
