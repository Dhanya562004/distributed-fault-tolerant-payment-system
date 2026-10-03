package com.paymentsystem.dto.response;

import java.time.Instant;
import java.util.Map;

public class SystemMetricsResponse {

    private long totalTransactions;
    private long successfulTransactions;
    private long failedTransactions;
    private long retryCount;
    private long idempotencyHits;
    private long dlqCount;
    private double successRatePercent;
    private double averageLatencyMs;
    private long activeQueueDepth;
    private Map<String, Long> statusBreakdown;
    private Instant timestamp;

    public SystemMetricsResponse() {
        this.timestamp = Instant.now();
    }

    public long getTotalTransactions() {
        return totalTransactions;
    }

    public void setTotalTransactions(long totalTransactions) {
        this.totalTransactions = totalTransactions;
    }

    public long getSuccessfulTransactions() {
        return successfulTransactions;
    }

    public void setSuccessfulTransactions(long successfulTransactions) {
        this.successfulTransactions = successfulTransactions;
    }

    public long getFailedTransactions() {
        return failedTransactions;
    }

    public void setFailedTransactions(long failedTransactions) {
        this.failedTransactions = failedTransactions;
    }

    public long getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(long retryCount) {
        this.retryCount = retryCount;
    }

    public long getIdempotencyHits() {
        return idempotencyHits;
    }

    public void setIdempotencyHits(long idempotencyHits) {
        this.idempotencyHits = idempotencyHits;
    }

    public long getDlqCount() {
        return dlqCount;
    }

    public void setDlqCount(long dlqCount) {
        this.dlqCount = dlqCount;
    }

    public double getSuccessRatePercent() {
        return successRatePercent;
    }

    public void setSuccessRatePercent(double successRatePercent) {
        this.successRatePercent = successRatePercent;
    }

    public double getAverageLatencyMs() {
        return averageLatencyMs;
    }

    public void setAverageLatencyMs(double averageLatencyMs) {
        this.averageLatencyMs = averageLatencyMs;
    }

    public long getActiveQueueDepth() {
        return activeQueueDepth;
    }

    public void setActiveQueueDepth(long activeQueueDepth) {
        this.activeQueueDepth = activeQueueDepth;
    }

    public Map<String, Long> getStatusBreakdown() {
        return statusBreakdown;
    }

    public void setStatusBreakdown(Map<String, Long> statusBreakdown) {
        this.statusBreakdown = statusBreakdown;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
