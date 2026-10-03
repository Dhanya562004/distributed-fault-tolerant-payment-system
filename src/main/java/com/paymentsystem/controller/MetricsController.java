package com.paymentsystem.controller;

import com.paymentsystem.dto.response.SystemMetricsResponse;
import com.paymentsystem.service.MetricsService;
import com.paymentsystem.worker.DistributedQueueManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Observability & Metrics", description = "Endpoints for real-time monitoring and metrics tracking")
public class MetricsController {

    private final MetricsService metricsService;
    private final DistributedQueueManager queueManager;

    @Autowired
    public MetricsController(MetricsService metricsService, DistributedQueueManager queueManager) {
        this.metricsService = metricsService;
        this.queueManager = queueManager;
    }

    @GetMapping("/api/v1/metrics")
    @Operation(summary = "Fetch System Metrics", description = "Returns real-time request latency, success rate, retries, idempotency hits, and queue depth")
    public ResponseEntity<SystemMetricsResponse> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetrics(queueManager.getTotalQueueDepth()));
    }

    @GetMapping("/metrics")
    @Operation(summary = "Fetch System Metrics (Root Alias)", description = "Alias endpoint required by system specification")
    public ResponseEntity<SystemMetricsResponse> getRootMetrics() {
        return ResponseEntity.ok(metricsService.getMetrics(queueManager.getTotalQueueDepth()));
    }
}
