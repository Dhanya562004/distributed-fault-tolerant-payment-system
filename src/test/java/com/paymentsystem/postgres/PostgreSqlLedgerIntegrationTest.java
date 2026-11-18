package com.paymentsystem.postgres;
import com.paymentsystem.ledger.LedgerEntryType;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.ReconciliationResult;
import com.paymentsystem.ledger.EntrySide;
import com.paymentsystem.ledger.LedgerEntry;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.ledger.LedgerEntryRepository;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.runner.LedgerOpeningBalanceBackfillRunner;
import com.paymentsystem.ledger.LedgerReconciliationService;
import com.paymentsystem.payment.PaymentProcessingService;
import com.paymentsystem.payment.RefundProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Double-Entry Ledger, unique constraint uk_ledger_tx_side,
 * and SQL-based reconciliation on genuine PostgreSQL 16 via Testcontainers.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class PostgreSqlLedgerIntegrationTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private RefundProcessingService refundProcessingService;

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

    private final String pgUserCode = "USR_PG_LEDGER";

    @BeforeEach
    void setUp() {
        userRepository.findByUserCode(pgUserCode).orElseGet(() ->
                userRepository.saveAndFlush(new User(pgUserCode, "PG Ledger User", "pg_ledger@example.com", 500000L)));
        backfillRunner.backfillOpeningBalances();
    }

    @Test
    @DisplayName("PostgreSQL: Atomic payment writes matching double-entry ledger rows")
    void testPostgreSql_NormalPayment_WritesDoubleEntryLedger() {
        User userBefore = userRepository.findByUserCode(pgUserCode).orElseThrow();
        long balanceBefore = userBefore.getBalance();

        long paymentAmount = 25000L;
        String paymentId = "PAY_PG_LED_" + UUID.randomUUID().toString().substring(0, 8);
        String idemKey = "IDEM_PG_LED_" + UUID.randomUUID().toString().substring(0, 8);

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idemKey,
                pgUserCode,
                paymentAmount,
                "USD",
                "CREDIT_CARD",
                "PG Double Entry Test"
        );

        PaymentResponse payment = paymentProcessingService.initiatePayment(req);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());

        // Balance updated in PG
        User userAfter = userRepository.findByUserCode(pgUserCode).orElseThrow();
        assertEquals(balanceBefore - paymentAmount, userAfter.getBalance());

        // Ledger entries in PG
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(payment.getPaymentId());
        assertEquals(2, entries.size());

        // Reconciliation passes
        ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(result.isBalanced(), "PostgreSQL double-entry reconciliation should be fully balanced");
        assertEquals(0, result.getTotalMismatches());
    }

    @Test
    @DisplayName("PostgreSQL: Full refund restores balance and writes matching settlement debit / user credit")
    void testPostgreSql_FullRefund_BalancedLedger() {
        User userBefore = userRepository.findByUserCode(pgUserCode).orElseThrow();
        long balanceBefore = userBefore.getBalance();

        long amount = 10000L;
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                "IDEM_PG_REF_" + UUID.randomUUID(),
                pgUserCode,
                amount,
                "USD",
                "CREDIT_CARD",
                "PG Refund Payment"
        );
        PaymentResponse payment = paymentProcessingService.initiatePayment(req);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());

        // Process refund
        refundProcessingService.processRefund(new ProcessRefundRequest(payment.getPaymentId(), "REF_IDEM_" + UUID.randomUUID(), amount, "PG full refund"));

        // Balance restored
        User userAfter = userRepository.findByUserCode(pgUserCode).orElseThrow();
        assertEquals(balanceBefore, userAfter.getBalance());

        // Reconciliation passes
        ReconciliationResult result = reconciliationService.runReconciliation(Duration.ZERO);
        assertTrue(result.isBalanced());
        assertEquals(0, result.getTotalMismatches());
    }

    @Test
    @DisplayName("PostgreSQL: Unique constraint uk_ledger_tx_side rejects duplicate entries")
    void testPostgreSql_UniqueConstraint_RejectsDuplicate() {
        String txId = "PG_DUP_TX_" + UUID.randomUUID().toString().substring(0, 8);

        LedgerEntry entry1 = new LedgerEntry(
                "LED_PG_1_" + UUID.randomUUID().toString().substring(0, 8),
                txId,
                LedgerEntryType.PAYMENT,
                "TEST_PG_DUP_ACCT",
                EntrySide.DEBIT,
                3000L,
                "USD",
                "PG debit 1",
                Instant.now()
        );
        LedgerEntry credit1 = new LedgerEntry(
                "LED_PG_1_CR_" + UUID.randomUUID().toString().substring(0, 8),
                txId,
                LedgerEntryType.PAYMENT,
                "SYSTEM_SETTLEMENT",
                EntrySide.CREDIT,
                3000L,
                "USD",
                "PG credit 1",
                Instant.now()
        );

        try {
            ledgerEntryRepository.saveAndFlush(entry1);
            ledgerEntryRepository.saveAndFlush(credit1);

            LedgerEntry duplicateDebit = new LedgerEntry(
                    "LED_PG_2_" + UUID.randomUUID().toString().substring(0, 8),
                    txId,
                    LedgerEntryType.PAYMENT,
                    "TEST_PG_DUP_ACCT",
                    EntrySide.DEBIT,
                    3000L,
                    "USD",
                    "PG duplicate debit",
                    Instant.now()
            );

            assertThrows(DataIntegrityViolationException.class, () -> {
                ledgerEntryRepository.saveAndFlush(duplicateDebit);
            });
        } finally {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txId);
        }
    }

    @Test
    @DisplayName("PostgreSQL: Reconciliation detects SQL-level balance tampering")
    void testPostgreSql_ReconciliationCatchesTampering() {
        long tamperAmount = 88888L;
        try {
            jdbcTemplate.update("UPDATE users SET balance = balance + ? WHERE user_code = ?", tamperAmount, pgUserCode);

            ReconciliationResult audit = reconciliationService.runReconciliation(Duration.ZERO);
            assertFalse(audit.isBalanced());
            assertTrue(audit.getTotalMismatches() >= 1);
        } finally {
            jdbcTemplate.update("UPDATE users SET balance = balance - ? WHERE user_code = ?", tamperAmount, pgUserCode);
            reconciliationService.runReconciliation(Duration.ZERO);
        }
    }
}
