package com.paymentsystem.service.impl;

import com.paymentsystem.dto.response.SystemMetricsResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.service.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsServiceImpl implements MetricsService {

    private final AtomicLong totalTransactions = new AtomicLong(0);
    private final AtomicLong successfulTransactions = new AtomicLong(0);
    private final AtomicLong failedTransactions = new AtomicLong(0);
    private final AtomicLong retryCount = new AtomicLong(0);
    private final AtomicLong idempotencyHits = new AtomicLong(0);
    private final AtomicLong dlqCount = new AtomicLong(0);
    private final AtomicLong latencySumMs = new AtomicLong(0);
    private final AtomicLong latencyCount = new AtomicLong(0);

    @Autowired(required = false)
    private PaymentTransactionRepository paymentTransactionRepository;

    @Override
    public void incrementTotalTransactions() {
        totalTransactions.incrementAndGet();
    }

    @Override
    public void incrementSuccessfulTransactions() {
        successfulTransactions.incrementAndGet();
    }

    @Override
    public void incrementFailedTransactions() {
        failedTransactions.incrementAndGet();
    }

    @Override
    public void recordRetry() {
        retryCount.incrementAndGet();
    }

    @Override
    public void recordIdempotencyHit() {
        idempotencyHits.incrementAndGet();
    }

    @Override
    public void recordDlqEntry() {
        dlqCount.incrementAndGet();
    }

    @Override
    public void recordLatency(long durationMs) {
        latencySumMs.addAndGet(durationMs);
        latencyCount.incrementAndGet();
    }

    @Override
    public SystemMetricsResponse getMetrics(long currentQueueDepth) {
        SystemMetricsResponse response = new SystemMetricsResponse();
        long total = totalTransactions.get();
        long success = successfulTransactions.get();
        long failed = failedTransactions.get();
        long retries = retryCount.get();
        long idHits = idempotencyHits.get();
        long dlq = dlqCount.get();
        long count = latencyCount.get();

        double avgLatency = count > 0 ? (double) latencySumMs.get() / count : 0.0;
        double successRate = (success + failed) > 0 ? ((double) success / (success + failed)) * 100.0 : 100.0;

        response.setTotalTransactions(total);
        response.setSuccessfulTransactions(success);
        response.setFailedTransactions(failed);
        response.setRetryCount(retries);
        response.setIdempotencyHits(idHits);
        response.setDlqCount(dlq);
        response.setSuccessRatePercent(Math.round(successRate * 100.0) / 100.0);
        response.setAverageLatencyMs(Math.round(avgLatency * 100.0) / 100.0);
        response.setActiveQueueDepth(currentQueueDepth);

        Map<String, Long> statusMap = new HashMap<>();
        if (paymentTransactionRepository != null) {
            try {
                for (PaymentStatus status : PaymentStatus.values()) {
                    statusMap.put(status.name(), paymentTransactionRepository.countByStatus(status));
                }
            } catch (Exception e) {
                statusMap.put("SUCCESS", success);
                statusMap.put("FAILED", failed);
            }
        } else {
            statusMap.put("SUCCESS", success);
            statusMap.put("FAILED", failed);
        }
        response.setStatusBreakdown(statusMap);

        return response;
    }
}
