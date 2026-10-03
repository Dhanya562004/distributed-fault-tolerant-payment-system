package com.paymentsystem.entity.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "events")
public class EventLogDocument {

    @Id
    private String id;
    private String eventId;
    private String eventType;
    private String paymentId;
    private String partitionKey;
    private Integer partitionId;
    private String payload;
    private String status; // QUEUED, PROCESSING, ACKNOWLEDGED, DLQ
    private Integer retryCount;
    private Instant createdAt;

    public EventLogDocument() {
    }

    public EventLogDocument(String eventId, String eventType, String paymentId, String partitionKey, Integer partitionId, String payload, String status, Integer retryCount) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.paymentId = paymentId;
        this.partitionKey = partitionKey;
        this.partitionId = partitionId;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    public Integer getPartitionId() {
        return partitionId;
    }

    public void setPartitionId(Integer partitionId) {
        this.partitionId = partitionId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
