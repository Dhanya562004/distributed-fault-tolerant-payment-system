package com.paymentsystem.repository;

import com.paymentsystem.entity.audit.EventLogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MongoEventLogRepository extends MongoRepository<EventLogDocument, String> {

    List<EventLogDocument> findByPaymentId(String paymentId);

    List<EventLogDocument> findByStatus(String status);
}
