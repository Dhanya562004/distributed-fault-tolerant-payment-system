package com.paymentsystem.service.impl;

import com.paymentsystem.dto.response.AuditLogResponse;
import com.paymentsystem.entity.audit.AuditLogDocument;
import com.paymentsystem.repository.MongoAuditLogRepository;
import com.paymentsystem.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final MongoAuditLogRepository mongoAuditLogRepository;
    private final Queue<AuditLogDocument> fallbackInMemoryLogs = new ConcurrentLinkedQueue<>();

    public AuditServiceImpl(@Autowired(required = false) MongoAuditLogRepository mongoAuditLogRepository) {
        this.mongoAuditLogRepository = mongoAuditLogRepository;
    }

    @Override
    public void recordAuditLog(String paymentId, String idempotencyKey, String userId, String previousStatus, String newStatus, String action, String workerId, String detail, Map<String, Object> metadata) {
        AuditLogDocument doc = new AuditLogDocument(
                paymentId,
                idempotencyKey,
                userId,
                previousStatus,
                newStatus,
                action,
                workerId,
                detail,
                metadata
        );

        logger.info("[AUDIT LOG] Payment: {} | User: {} | {} -> {} | Action: {} | Worker: {}",
                paymentId, userId, previousStatus, newStatus, action, workerId);

        boolean savedToMongo = false;
        if (mongoAuditLogRepository != null) {
            try {
                mongoAuditLogRepository.save(doc);
                savedToMongo = true;
            } catch (Exception e) {
                logger.warn("Could not save audit log to MongoDB (using in-memory fallback): {}", e.getMessage());
            }
        }

        if (!savedToMongo) {
            doc.setId(UUID.randomUUID().toString());
            fallbackInMemoryLogs.add(doc);
            if (fallbackInMemoryLogs.size() > 2000) {
                fallbackInMemoryLogs.poll();
            }
        }
    }

    @Override
    public List<AuditLogResponse> getLogsForPayment(String paymentId) {
        if (mongoAuditLogRepository != null) {
            try {
                List<AuditLogDocument> docs = mongoAuditLogRepository.findByPaymentIdOrderByTimestampDesc(paymentId);
                if (!docs.isEmpty()) {
                    return docs.stream().map(this::mapToResponse).collect(Collectors.toList());
                }
            } catch (Exception e) {
                logger.warn("Failed fetching logs from MongoDB, reading from memory fallback", e);
            }
        }

        return fallbackInMemoryLogs.stream()
                .filter(doc -> Objects.equals(doc.getPaymentId(), paymentId))
                .sorted(Comparator.comparing(AuditLogDocument::getTimestamp).reversed())
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLogResponse> getAllLogs() {
        if (mongoAuditLogRepository != null) {
            try {
                List<AuditLogDocument> docs = mongoAuditLogRepository.findTop100ByOrderByTimestampDesc();
                if (!docs.isEmpty()) {
                    return docs.stream().map(this::mapToResponse).collect(Collectors.toList());
                }
            } catch (Exception e) {
                logger.warn("Failed fetching logs from MongoDB, reading from memory fallback", e);
            }
        }

        return fallbackInMemoryLogs.stream()
                .sorted(Comparator.comparing(AuditLogDocument::getTimestamp).reversed())
                .limit(100)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private AuditLogResponse mapToResponse(AuditLogDocument doc) {
        AuditLogResponse res = new AuditLogResponse();
        res.setId(doc.getId());
        res.setPaymentId(doc.getPaymentId());
        res.setIdempotencyKey(doc.getIdempotencyKey());
        res.setUserId(doc.getUserId());
        res.setPreviousStatus(doc.getPreviousStatus());
        res.setNewStatus(doc.getNewStatus());
        res.setAction(doc.getAction());
        res.setWorkerId(doc.getWorkerId());
        res.setDetail(doc.getDetail());
        res.setMetadata(doc.getMetadata());
        res.setTimestamp(doc.getTimestamp());
        return res;
    }
}
