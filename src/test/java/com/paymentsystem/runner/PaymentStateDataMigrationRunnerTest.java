package com.paymentsystem.runner;

import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.RefundTransaction;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.RefundTransactionRepository;
import com.paymentsystem.test.PaymentTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaymentStateDataMigrationRunnerTest {

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private RefundTransactionRepository refundRepository;

    @Autowired
    private PaymentStateDataMigrationRunner migrationRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Data fix runner restores payments with partial refunds from REFUNDED to SUCCESS, keeps full refunds REFUNDED, is idempotent")
    void testDataFixRunner_RestoresPartialRefunds_Idempotent() {
        String payPartialId = "PAY_LEGACY_PARTIAL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String payFullId = "PAY_LEGACY_FULL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String payZeroId = "PAY_LEGACY_ZERO_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 1. Seed legacy payments via direct SQL to simulate legacy database rows with invalid REFUNDED status
        jdbcTemplate.update("""
                INSERT INTO payments (payment_id, idempotency_key, user_id, amount, currency, status, payment_method, retry_count, created_at, updated_at)
                VALUES (?, ?, 'USR_TEST', 10000, 'USD', 'REFUNDED', 'CREDIT_CARD', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, payPartialId, "IDEM_" + payPartialId);

        jdbcTemplate.update("""
                INSERT INTO payments (payment_id, idempotency_key, user_id, amount, currency, status, payment_method, retry_count, created_at, updated_at)
                VALUES (?, ?, 'USR_TEST', 10000, 'USD', 'REFUNDED', 'CREDIT_CARD', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, payFullId, "IDEM_" + payFullId);

        jdbcTemplate.update("""
                INSERT INTO payments (payment_id, idempotency_key, user_id, amount, currency, status, payment_method, retry_count, created_at, updated_at)
                VALUES (?, ?, 'USR_TEST', 10000, 'USD', 'REFUNDED', 'CREDIT_CARD', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, payZeroId, "IDEM_" + payZeroId);

        // 2. Seed refund rows: payPartial has 4000 (less than 10000); payFull has 10000; payZero has 0
        refundRepository.saveAndFlush(new RefundTransaction("REF_PARTIAL_1", payPartialId, "USR_TEST", 4000L, PaymentStatus.REFUNDED, "Partial 40%"));
        refundRepository.saveAndFlush(new RefundTransaction("REF_FULL_1", payFullId, "USR_TEST", 6000L, PaymentStatus.REFUNDED, "Part 1"));
        refundRepository.saveAndFlush(new RefundTransaction("REF_FULL_2", payFullId, "USR_TEST", 4000L, PaymentStatus.REFUNDED, "Part 2"));

        // 3. Execute migration runner
        migrationRunner.run(null);

        // 4. Verify outcomes:
        // Partial refund payment (4000 < 10000) must be reverted to SUCCESS
        PaymentTransaction partialTx = paymentRepository.findByPaymentId(payPartialId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, partialTx.getStatus(), "Partial refund payment must be reverted to SUCCESS");

        // Zero refund payment (0 < 10000) must be reverted to SUCCESS
        PaymentTransaction zeroTx = paymentRepository.findByPaymentId(payZeroId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, zeroTx.getStatus(), "Zero refund payment must be reverted to SUCCESS");

        // Full refund payment (10000 == 10000) must remain REFUNDED
        PaymentTransaction fullTx = paymentRepository.findByPaymentId(payFullId).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, fullTx.getStatus(), "Full refund payment must remain in REFUNDED state");

        // 5. Run runner a second time to verify idempotency (safe to run multiple times)
        assertDoesNotThrow(() -> migrationRunner.run(null));
        PaymentTransaction partialTx2 = paymentRepository.findByPaymentId(payPartialId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, partialTx2.getStatus(), "Second run must preserve SUCCESS state");
    }
}
