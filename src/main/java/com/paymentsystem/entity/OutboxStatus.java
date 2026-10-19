package com.paymentsystem.entity;

/**
 * Lifecycle states of an outbox webhook event.
 */
public enum OutboxStatus {
    /**
     * Event has been committed to the database and is awaiting initial delivery or scheduled retry.
     */
    PENDING,

    /**
     * Event has been claimed by a delivery worker under an active lease.
     */
    PROCESSING,

    /**
     * Event has been acknowledged with HTTP 2xx by the destination endpoint.
     */
    DELIVERED,

    /**
     * Event has exhausted maximum retry attempts or encountered a non-retryable failure.
     * Retained permanently for auditing and manual redelivery.
     */
    DEAD_LETTER
}
