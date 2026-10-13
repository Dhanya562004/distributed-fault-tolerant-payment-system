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
        long dbTotal = (paymentTransactionRepository != null) ? paymentTransactionRepository.count() : 0;
        long total = Math.max(totalTransactions.get(), dbTotal);
        long dbSuccess = (paymentTransactionRepository != null) ? paymentTransactionRepository.countByStatus(PaymentStatus.SUCCESS) : 0;
        long success = Math.max(successfulTransactions.get(), dbSuccess);
        long dbFailed = (paymentTransactionRepository != null) ? paymentTransactionRepository.countByStatus(PaymentStatus.FAILED) : 0;
        long failed = Math.max(failedTransactions.get(), dbFailed);
        long dbRetries = 0;
        if (paymentTransactionRepository != null) {
            try {
                dbRetries = paymentTransactionRepository.findAll().stream()
                        .mapToLong(p -> p.getRetryCount() != null ? p.getRetryCount() : 0)
                        .sum();
            } catch (Exception ignored) {
            }
        }
        long retries = Math.max(retryCount.get(), dbRetries);
        long idHits = idempotencyHits.get();
        long dlq = dlqCount.get();
        long count = latencyCount.get();

        double avgLatency = count > 0 ? (double) latencySumMs.get() / count : 0.0;
        double successRate = (success + failed) > 0 ? ((double) success / (success + failed)) * 100.0 : (total > 0 ? 100.0 : 0.0);

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
