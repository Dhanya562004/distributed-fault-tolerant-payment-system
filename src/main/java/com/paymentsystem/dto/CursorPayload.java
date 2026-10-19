package com.paymentsystem.dto;

import java.time.Instant;

/**
 * Validated in-memory representation of decoded keyset cursor coordinates.
 */
public class CursorPayload {

    private final Instant createdAt;
    private final Long id;

    public CursorPayload(Instant createdAt, Long id) {
        this.createdAt = createdAt;
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getId() {
        return id;
    }

    @Override
    public String toString() {
        return "CursorPayload{" +
                "createdAt=" + createdAt +
                ", id=" + id +
                '}';
    }
}
