package com.paymentsystem.dto.response;

public class UserBalanceDiscrepancy {
    private String userCode;
    private long userBalance;
    private long ledgerBalance;
    private long discrepancy;

    public UserBalanceDiscrepancy() {}

    public UserBalanceDiscrepancy(String userCode, long userBalance, long ledgerBalance, long discrepancy) {
        this.userCode = userCode;
        this.userBalance = userBalance;
        this.ledgerBalance = ledgerBalance;
        this.discrepancy = discrepancy;
    }

    public String getUserCode() { return userCode; }
    public void setUserCode(String userCode) { this.userCode = userCode; }

    public long getUserBalance() { return userBalance; }
    public void setUserBalance(long userBalance) { this.userBalance = userBalance; }

    public long getLedgerBalance() { return ledgerBalance; }
    public void setLedgerBalance(long ledgerBalance) { this.ledgerBalance = ledgerBalance; }

    public long getDiscrepancy() { return discrepancy; }
    public void setDiscrepancy(long discrepancy) { this.discrepancy = discrepancy; }
}
