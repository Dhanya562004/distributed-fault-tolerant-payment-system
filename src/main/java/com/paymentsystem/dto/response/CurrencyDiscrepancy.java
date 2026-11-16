package com.paymentsystem.dto.response;

public class CurrencyDiscrepancy {
    private String currency;
    private long totalDebits;
    private long totalCredits;
    private long difference;

    public CurrencyDiscrepancy() {}

    public CurrencyDiscrepancy(String currency, long totalDebits, long totalCredits, long difference) {
        this.currency = currency;
        this.totalDebits = totalDebits;
        this.totalCredits = totalCredits;
        this.difference = difference;
    }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public long getTotalDebits() { return totalDebits; }
    public void setTotalDebits(long totalDebits) { this.totalDebits = totalDebits; }

    public long getTotalCredits() { return totalCredits; }
    public void setTotalCredits(long totalCredits) { this.totalCredits = totalCredits; }

    public long getDifference() { return difference; }
    public void setDifference(long difference) { this.difference = difference; }
}
