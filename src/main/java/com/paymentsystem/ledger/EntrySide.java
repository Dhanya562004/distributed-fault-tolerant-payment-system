package com.paymentsystem.ledger;

/**
 * Double-entry ledger entry side.
 * In double-entry accounting:
 * - DEBIT: increases assets/expenses, decreases liabilities/equity/user deposit balances.
 * - CREDIT: increases liabilities/equity/user deposit balances, decreases assets/expenses.
 */
public enum EntrySide {
    DEBIT,
    CREDIT
}
