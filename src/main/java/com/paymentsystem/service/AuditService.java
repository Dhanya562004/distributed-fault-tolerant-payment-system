package com.paymentsystem.service;

import com.paymentsystem.dto.response.AuditLogResponse;

import java.util.List;
import java.util.Map;

public interface AuditService {
    void recordAuditLog(String paymentId, String idempotencyKey, String userId, String previousStatus, String newStatus, String action, String workerId, String detail, Map<String, Object> metadata);
    List<AuditLogResponse> getLogsForPayment(String paymentId);
    List<AuditLogResponse> getAllLogs();
}
