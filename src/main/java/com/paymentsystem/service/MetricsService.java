package com.paymentsystem.service;

import com.paymentsystem.dto.response.SystemMetricsResponse;

public interface MetricsService {
    void incrementTotalTransactions();
    void incrementSuccessfulTransactions();
    void incrementFailedTransactions();
    void recordRetry();
    void recordIdempotencyHit();
    void recordDlqEntry();
    void recordLatency(long durationMs);
    SystemMetricsResponse getMetrics(long currentQueueDepth);
}
