package com.paymentsystem.repository;

import com.paymentsystem.entity.audit.AuditLogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MongoAuditLogRepository extends MongoRepository<AuditLogDocument, String> {

    List<AuditLogDocument> findByPaymentIdOrderByTimestampDesc(String paymentId);

    List<AuditLogDocument> findByUserIdOrderByTimestampDesc(String userId);

    List<AuditLogDocument> findTop100ByOrderByTimestampDesc();
}
