package com.paymentsystem.dto.response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ReconciliationResult {

    private Instant runTime;
    private boolean balanced;
    private long totalMismatches;
    private List<CurrencyDiscrepancy> currencyDiscrepancies = new ArrayList<>();
    private List<UserBalanceDiscrepancy> userDiscrepancies = new ArrayList<>();

    public ReconciliationResult() {
        this.runTime = Instant.now();
    }

    public ReconciliationResult(Instant runTime,
                                boolean balanced,
                                long totalMismatches,
                                List<CurrencyDiscrepancy> currencyDiscrepancies,
                                List<UserBalanceDiscrepancy> userDiscrepancies) {
        this.runTime = runTime;
        this.balanced = balanced;
        this.totalMismatches = totalMismatches;
        this.currencyDiscrepancies = (currencyDiscrepancies != null) ? currencyDiscrepancies : new ArrayList<>();
        this.userDiscrepancies = (userDiscrepancies != null) ? userDiscrepancies : new ArrayList<>();
    }

    public Instant getRunTime() { return runTime; }
    public void setRunTime(Instant runTime) { this.runTime = runTime; }

    public boolean isBalanced() { return balanced; }
    public void setBalanced(boolean balanced) { this.balanced = balanced; }

    public long getTotalMismatches() { return totalMismatches; }
    public void setTotalMismatches(long totalMismatches) { this.totalMismatches = totalMismatches; }

    public List<CurrencyDiscrepancy> getCurrencyDiscrepancies() { return currencyDiscrepancies; }
    public void setCurrencyDiscrepancies(List<CurrencyDiscrepancy> currencyDiscrepancies) { this.currencyDiscrepancies = currencyDiscrepancies; }

    public List<UserBalanceDiscrepancy> getUserDiscrepancies() { return userDiscrepancies; }
    public void setUserDiscrepancies(List<UserBalanceDiscrepancy> userDiscrepancies) { this.userDiscrepancies = userDiscrepancies; }
}
