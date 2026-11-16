package com.paymentsystem.service.impl;

import com.paymentsystem.entity.EntrySide;
import com.paymentsystem.entity.LedgerEntry;
import com.paymentsystem.entity.LedgerEntryType;
import com.paymentsystem.repository.LedgerEntryRepository;
import com.paymentsystem.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerServiceImpl implements LedgerService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerServiceImpl.class);

    public static final String ACCOUNT_SYSTEM_SETTLEMENT = "SYSTEM_SETTLEMENT";
    public static final String ACCOUNT_SYSTEM_RESERVE = "SYSTEM_RESERVE";

    private final LedgerEntryRepository ledgerEntryRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerServiceImpl(LedgerEntryRepository ledgerEntryRepository) {
        this(ledgerEntryRepository, Clock.systemUTC());
    }

    public LedgerServiceImpl(LedgerEntryRepository ledgerEntryRepository, Clock clock) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<LedgerEntry> recordPaymentEntries(String paymentId, String userId, long amount, String currency, String description) {
        Instant now = Instant.now(clock);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "USD";

        // Entry 1: DEBIT Customer Account
        String debitEntryId = "LED_PAY_DR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LedgerEntry debitEntry = new LedgerEntry(
                debitEntryId,
                paymentId,
                LedgerEntryType.PAYMENT,
                userId,
                EntrySide.DEBIT,
                amount,
                curr,
                description != null ? description : "Payment debit for " + paymentId,
                now
        );

        // Entry 2: CREDIT Settlement Account
        String creditEntryId = "LED_PAY_CR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LedgerEntry creditEntry = new LedgerEntry(
                creditEntryId,
                paymentId,
                LedgerEntryType.PAYMENT,
                ACCOUNT_SYSTEM_SETTLEMENT,
                EntrySide.CREDIT,
                amount,
                curr,
                "Settlement credit for " + paymentId,
                now
        );

        LedgerEntry savedDebit = ledgerEntryRepository.save(debitEntry);
        LedgerEntry savedCredit = ledgerEntryRepository.save(creditEntry);

        logger.info("[DOUBLE-ENTRY LEDGER] Recorded matching entries for payment {}: DEBIT {} ({}), CREDIT {} ({})",
                paymentId, userId, amount, ACCOUNT_SYSTEM_SETTLEMENT, amount);

        return List.of(savedDebit, savedCredit);
    }

    @Override
    @Transactional
    public List<LedgerEntry> recordRefundEntries(String refundId, String userId, long amount, String currency, String description) {
        Instant now = Instant.now(clock);
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "USD";

        // Entry 1: DEBIT Settlement Account
        String debitEntryId = "LED_REF_DR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LedgerEntry debitEntry = new LedgerEntry(
                debitEntryId,
                refundId,
                LedgerEntryType.REFUND,
                ACCOUNT_SYSTEM_SETTLEMENT,
                EntrySide.DEBIT,
                amount,
                curr,
                "Settlement refund debit for " + refundId,
                now
        );

        // Entry 2: CREDIT Customer Account
        String creditEntryId = "LED_REF_CR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LedgerEntry creditEntry = new LedgerEntry(
                creditEntryId,
                refundId,
                LedgerEntryType.REFUND,
                userId,
                EntrySide.CREDIT,
                amount,
                curr,
                description != null ? description : "Refund credit for " + refundId,
                now
        );

        LedgerEntry savedDebit = ledgerEntryRepository.save(debitEntry);
        LedgerEntry savedCredit = ledgerEntryRepository.save(creditEntry);

        logger.info("[DOUBLE-ENTRY LEDGER] Recorded matching entries for refund {}: DEBIT {} ({}), CREDIT {} ({})",
                refundId, ACCOUNT_SYSTEM_SETTLEMENT, amount, userId, amount);

        return List.of(savedDebit, savedCredit);
    }

    @Override
    @Transactional
    public List<LedgerEntry> recordOpeningBalance(String userCode, long amount, String currency) {
        if (amount <= 0) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        String txId = "OB_" + userCode;
        String curr = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "USD";

        if (ledgerEntryRepository.existsByTransactionIdAndEntrySide(txId, EntrySide.CREDIT)) {
            logger.debug("[DOUBLE-ENTRY LEDGER] Opening balance entries already exist for user {}", userCode);
            return ledgerEntryRepository.findByTransactionId(txId);
        }

        // Entry 1: DEBIT System Reserve
        String debitEntryId = "LED_OB_DR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LedgerEntry debitEntry = new LedgerEntry(
                debitEntryId,
                txId,
                LedgerEntryType.OPENING_BALANCE,
                ACCOUNT_SYSTEM_RESERVE,
                EntrySide.DEBIT,
                amount,
                curr,
                "Opening balance equity reserve allocation for " + userCode,
                now
        );

        // Entry 2: CREDIT Customer Account
        String creditEntryId = "LED_OB_CR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LedgerEntry creditEntry = new LedgerEntry(
                creditEntryId,
                txId,
                LedgerEntryType.OPENING_BALANCE,
                userCode,
                EntrySide.CREDIT,
                amount,
                curr,
                "Opening balance deposit credit for " + userCode,
                now
        );

        LedgerEntry savedDebit = ledgerEntryRepository.save(debitEntry);
        LedgerEntry savedCredit = ledgerEntryRepository.save(creditEntry);

        logger.info("[DOUBLE-ENTRY LEDGER] Recorded opening balance for user {}: DEBIT {} ({}), CREDIT {} ({})",
                userCode, ACCOUNT_SYSTEM_RESERVE, amount, userCode, amount);

        return List.of(savedDebit, savedCredit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> getEntriesByTransactionId(String transactionId) {
        return ledgerEntryRepository.findByTransactionId(transactionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> getEntriesByAccountId(String accountId) {
        return ledgerEntryRepository.findByAccountId(accountId);
    }
}
