package com.paymentsystem.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup backfill runner for opening balances in the double-entry ledger.
 *
 * Ensures existing users with initial balances (e.g. from seed data or prior migrations)
 * have matching double-entry ledger rows (CREDIT to user account, DEBIT to SYSTEM_RESERVE)
 * so that day-one ledger reconciliation checks succeed without false alarms.
 *
 * Safe to execute multiple times across application restarts (guarded by NOT EXISTS checks).
 */
@Component
@Order(15)
public class LedgerOpeningBalanceBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LedgerOpeningBalanceBackfillRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public LedgerOpeningBalanceBackfillRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillOpeningBalances();
    }

    public int backfillOpeningBalances() {
        try {
            // 1. Backfill CREDIT rows for user deposit balances
            String creditSql = """
                    INSERT INTO ledger_entries (entry_id, transaction_id, entry_type, account_id, entry_side, amount, currency, description, created_at)
                    SELECT 
                        CONCAT('LED_OB_CR_', u.user_code),
                        CONCAT('OB_', u.user_code),
                        'OPENING_BALANCE',
                        u.user_code,
                        'CREDIT',
                        u.balance,
                        'USD',
                        'Opening balance initial ledger credit',
                        COALESCE(u.created_at, CURRENT_TIMESTAMP)
                    FROM users u
                    WHERE u.balance > 0
                      AND NOT EXISTS (
                          SELECT 1 FROM ledger_entries l 
                          WHERE l.transaction_id = CONCAT('OB_', u.user_code) 
                            AND l.entry_side = 'CREDIT'
                      )
                    """;

            // 2. Backfill matching DEBIT rows for system equity reserve
            String debitSql = """
                    INSERT INTO ledger_entries (entry_id, transaction_id, entry_type, account_id, entry_side, amount, currency, description, created_at)
                    SELECT 
                        CONCAT('LED_OB_DR_', u.user_code),
                        CONCAT('OB_', u.user_code),
                        'OPENING_BALANCE',
                        'SYSTEM_RESERVE',
                        'DEBIT',
                        u.balance,
                        'USD',
                        'Opening balance offsetting reserve debit',
                        COALESCE(u.created_at, CURRENT_TIMESTAMP)
                    FROM users u
                    WHERE u.balance > 0
                      AND NOT EXISTS (
                          SELECT 1 FROM ledger_entries l 
                          WHERE l.transaction_id = CONCAT('OB_', u.user_code) 
                            AND l.entry_side = 'DEBIT'
                      )
                    """;

            int creditsInserted = jdbcTemplate.update(creditSql);
            int debitsInserted = jdbcTemplate.update(debitSql);

            if (creditsInserted > 0 || debitsInserted > 0) {
                logger.info("[LEDGER BACKFILL] Successfully created {} matching opening-balance double-entry ledger rows for existing users",
                        creditsInserted + debitsInserted);
            } else {
                logger.debug("[LEDGER BACKFILL] All existing user balances already possess matching opening-balance ledger records.");
            }
            return creditsInserted;
        } catch (Exception e) {
            logger.warn("[LEDGER BACKFILL] Unable to run ledger opening-balance backfill (e.g. table not yet created): {}", e.getMessage());
            return 0;
        }
    }
}
