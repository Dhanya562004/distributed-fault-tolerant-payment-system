package com.paymentsystem.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Deterministic mutable clock implementation for time-travel testing without Thread.sleep.
 */
public class MutableClock extends Clock {

    private Instant currentInstant;
    private final ZoneId zone;

    public MutableClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = initialInstant;
        this.zone = zone;
    }

    public MutableClock(Instant initialInstant) {
        this(initialInstant, ZoneOffset.UTC);
    }

    public synchronized void setInstant(Instant newInstant) {
        this.currentInstant = newInstant;
    }

    public synchronized void advance(Duration duration) {
        this.currentInstant = this.currentInstant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(currentInstant, zone);
    }

    @Override
    public synchronized Instant instant() {
        return currentInstant;
    }
}
