package com.paymentsystem.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized payload structure delivered to merchant webhook endpoints.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookEventPayload {

    private String eventId;
    private String eventType;
    private String aggregateId;
    private Instant timestamp;
    private Map<String, Object> data;

    public WebhookEventPayload() {
    }

    public WebhookEventPayload(String eventId, String eventType, String aggregateId, Instant timestamp, Map<String, Object> data) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.timestamp = timestamp;
        this.data = data;
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

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
