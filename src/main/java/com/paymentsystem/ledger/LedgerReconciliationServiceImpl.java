package com.paymentsystem.ledger;

import com.paymentsystem.dto.response.CurrencyDiscrepancy;
import com.paymentsystem.dto.response.ReconciliationResult;
import com.paymentsystem.dto.response.UserBalanceDiscrepancy;
import com.paymentsystem.runner.LedgerOpeningBalanceBackfillRunner;
import com.paymentsystem.ledger.LedgerReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LedgerReconciliationServiceImpl implements LedgerReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerReconciliationServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final LedgerOpeningBalanceBackfillRunner backfillRunner;

    private final AtomicLong mismatchCount = new AtomicLong(0);
    private final AtomicReference<Instant> lastRunTime = new AtomicReference<>(null);
    private final AtomicReference<String> reconciliationStatus = new AtomicReference<>("INITIALIZING");

    @org.springframework.beans.factory.annotation.Value("${app.ledger.enabled:true}")
    private boolean ledgerEnabled = true;

    public void setLedgerEnabled(boolean ledgerEnabled) {
        this.ledgerEnabled = ledgerEnabled;
    }

    public boolean isLedgerEnabled() {
        return ledgerEnabled;
    }

    @Autowired
    public LedgerReconciliationServiceImpl(JdbcTemplate jdbcTemplate,
                                           @Autowired(required = false) LedgerOpeningBalanceBackfillRunner backfillRunner) {
        this(jdbcTemplate, backfillRunner, Clock.systemUTC());
    }

    public LedgerReconciliationServiceImpl(JdbcTemplate jdbcTemplate,
                                           LedgerOpeningBalanceBackfillRunner backfillRunner,
                                           Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.backfillRunner = backfillRunner;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationResult runReconciliation() {
        return runReconciliation(Duration.ofSeconds(2));
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationResult runReconciliation(Duration cutoffDuration) {
        Instant now = Instant.now(clock);
        if (!ledgerEnabled) {
            logger.info("[LEDGER RECONCILIATION] Ledger engine is disabled via feature flag app.ledger.enabled=false. Reconciliation skipped.");
            this.reconciliationStatus.set("DISABLED");
            return new ReconciliationResult(now, true, 0, List.of(), List.of());
        }

        Instant cutoff = (cutoffDuration != null && !cutoffDuration.isZero() && !cutoffDuration.isNegative())
                ? now.minus(cutoffDuration)
                : now;

        Timestamp cutoffTimestamp = Timestamp.from(cutoff);

        // 1. Check Debits = Credits PER CURRENCY using SQL SUMs
        String currencyCheckSql = """
                SELECT currency,
                       COALESCE(SUM(CASE WHEN entry_side = 'DEBIT' THEN amount ELSE 0 END), 0) AS total_debit,
                       COALESCE(SUM(CASE WHEN entry_side = 'CREDIT' THEN amount ELSE 0 END), 0) AS total_credit
                FROM ledger_entries
                WHERE created_at <= ?
                GROUP BY currency
                HAVING COALESCE(SUM(CASE WHEN entry_side = 'DEBIT' THEN amount ELSE 0 END), 0) <>
                       COALESCE(SUM(CASE WHEN entry_side = 'CREDIT' THEN amount ELSE 0 END), 0)
                """;

        List<CurrencyDiscrepancy> currencyMismatches = jdbcTemplate.query(
                currencyCheckSql,
                ps -> ps.setTimestamp(1, cutoffTimestamp),
                (rs, rowNum) -> {
                    String currency = rs.getString("currency");
                    long debits = rs.getLong("total_debit");
                    long credits = rs.getLong("total_credit");
                    long diff = debits - credits;
                    logger.error("[RECONCILIATION MISMATCH] Currency {} imbalance detected! Debits: {}, Credits: {}, Diff: {}",
                            currency, debits, credits, diff);
                    return new CurrencyDiscrepancy(currency, debits, credits, diff);
                }
        );

        // 2. Check Each User Balance = sum(CREDIT) - sum(DEBIT) of their ledger rows using SQL SUMs
        String userCheckSql = """
                SELECT u.user_code,
                       u.balance AS user_balance,
                       COALESCE(SUM(CASE WHEN l.entry_side = 'CREDIT' THEN l.amount ELSE -l.amount END), 0) AS ledger_balance
                FROM users u
                LEFT JOIN ledger_entries l ON u.user_code = l.account_id AND l.created_at <= ?
                GROUP BY u.user_code, u.balance
                HAVING u.balance <> COALESCE(SUM(CASE WHEN l.entry_side = 'CREDIT' THEN l.amount ELSE -l.amount END), 0)
                """;

        List<UserBalanceDiscrepancy> userMismatches = jdbcTemplate.query(
                userCheckSql,
                ps -> ps.setTimestamp(1, cutoffTimestamp),
                (rs, rowNum) -> {
                    String userCode = rs.getString("user_code");
                    long userBalance = rs.getLong("user_balance");
                    long ledgerBalance = rs.getLong("ledger_balance");
                    long discrepancy = userBalance - ledgerBalance;
                    logger.error("[RECONCILIATION MISMATCH] User {} balance discrepancy detected! Table Balance: {}, Ledger Sum: {}, Diff: {}",
                            userCode, userBalance, ledgerBalance, discrepancy);
                    return new UserBalanceDiscrepancy(userCode, userBalance, ledgerBalance, discrepancy);
                }
        );

        long totalMismatches = currencyMismatches.size() + userMismatches.size();
        boolean balanced = (totalMismatches == 0);

        this.mismatchCount.set(totalMismatches);
        this.lastRunTime.set(now);
        this.reconciliationStatus.set(balanced ? "BALANCED" : "MISMATCH_DETECTED");

        if (balanced) {
            logger.info("[LEDGER RECONCILIATION] PASSED: All currencies balanced (Debits == Credits) and all user balances match ledger entries exactly (as of cutoff: {})", cutoff);
        } else {
            logger.error("[LEDGER RECONCILIATION] FAILED: Detected {} discrepancies ({} currency imbalances, {} user balance discrepancies) as of cutoff: {}",
                    totalMismatches, currencyMismatches.size(), userMismatches.size(), cutoff);
        }

        return new ReconciliationResult(now, balanced, totalMismatches, currencyMismatches, userMismatches);
    }

    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms:60000}", initialDelay = 10000)
    public void scheduledReconciliation() {
        if (!ledgerEnabled) {
            return;
        }
        try {
            runReconciliation();
        } catch (Exception e) {
            logger.warn("[LEDGER RECONCILIATION] Scheduled reconciliation job encountered an error: {}", e.getMessage());
        }
    }

    @Override
    public long getMismatchCount() {
        return mismatchCount.get();
    }

    @Override
    public Instant getLastRunTime() {
        return lastRunTime.get();
    }

    @Override
    public String getReconciliationStatus() {
        if (!ledgerEnabled) {
            return "DISABLED";
        }
        return reconciliationStatus.get();
    }
}
