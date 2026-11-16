package com.paymentsystem.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time startup migration runner that corrects legacy payment records
 * incorrectly marked as REFUNDED after only partial refunds (or zero refunds).
 *
 * Runs once at application startup using plain idempotent SQL.
 * Safe to execute multiple times across restarts.
 */
@Component
@Order(10)
public class PaymentStateDataMigrationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PaymentStateDataMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public PaymentStateDataMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String sql = """
                    UPDATE payments p
                    SET status = 'SUCCESS', updated_at = CURRENT_TIMESTAMP
                    WHERE p.status = 'REFUNDED'
                      AND (
                        SELECT COALESCE(SUM(r.amount), 0)
                        FROM refunds r
                        WHERE r.payment_id = p.payment_id
                      ) < p.amount
                    """;

            int rowsUpdated = jdbcTemplate.update(sql);
            if (rowsUpdated > 0) {
                logger.info("[DATA MIGRATION] Corrected {} legacy payment records from REFUNDED to SUCCESS where refund sum < payment amount", rowsUpdated);
            } else {
                logger.debug("[DATA MIGRATION] Payment state verification complete. No inconsistent REFUNDED rows found.");
            }
        } catch (Exception e) {
            logger.warn("[DATA MIGRATION] Unable to run payment state migration (e.g. tables not yet initialized): {}", e.getMessage());
        }
    }
}
