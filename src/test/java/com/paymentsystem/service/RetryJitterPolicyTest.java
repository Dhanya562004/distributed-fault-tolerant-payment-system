package com.paymentsystem.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class RetryJitterPolicyTest {

    @Test
    @DisplayName("Delay is never below 0 and never above the cap across multiple attempts")
    void testDelayNeverBelowZeroAndNeverAboveCap() {
        RetryJitterPolicy policy = new RetryJitterPolicy();
        long baseMs = 500;
        long capMs = 8000;

        for (int attempt = 1; attempt <= 15; attempt++) {
            for (int i = 0; i < 500; i++) {
                long delay = policy.calculateFullJitterDelay(attempt, baseMs, capMs);
                assertTrue(delay >= 0, "Delay must never be negative: " + delay);
                assertTrue(delay <= capMs, "Delay must never exceed cap " + capMs + ": " + delay);
            }
        }
    }

    @Test
    @DisplayName("Deterministic random source produces expected exact full jitter boundaries")
    void testInjectableRandomSource_BoundaryValues() {
        long baseMs = 500;
        long capMs = 8000;

        // When random is 0.0, delay must always be 0
        RetryJitterPolicy zeroPolicy = new RetryJitterPolicy(() -> 0.0);
        assertEquals(0, zeroPolicy.calculateFullJitterDelay(1, baseMs, capMs));
        assertEquals(0, zeroPolicy.calculateFullJitterDelay(2, baseMs, capMs));
        assertEquals(0, zeroPolicy.calculateFullJitterDelay(5, baseMs, capMs));

        // When random is 1.0, delay must equal the full exponential cap for that attempt
        RetryJitterPolicy maxPolicy = new RetryJitterPolicy(() -> 1.0);
        assertEquals(500, maxPolicy.calculateFullJitterDelay(1, baseMs, capMs)); // 500 * 2^0 = 500
        assertEquals(1000, maxPolicy.calculateFullJitterDelay(2, baseMs, capMs)); // 500 * 2^1 = 1000
        assertEquals(2000, maxPolicy.calculateFullJitterDelay(3, baseMs, capMs)); // 500 * 2^2 = 2000
        assertEquals(4000, maxPolicy.calculateFullJitterDelay(4, baseMs, capMs)); // 500 * 2^3 = 4000
        assertEquals(8000, maxPolicy.calculateFullJitterDelay(5, baseMs, capMs)); // 500 * 2^4 = 8000 (cap)
        assertEquals(8000, maxPolicy.calculateFullJitterDelay(10, baseMs, capMs)); // capped at 8000

        // When random is 0.5, delay must equal exactly half of the exponential cap
        RetryJitterPolicy halfPolicy = new RetryJitterPolicy(() -> 0.5);
        assertEquals(250, halfPolicy.calculateFullJitterDelay(1, baseMs, capMs));
        assertEquals(500, halfPolicy.calculateFullJitterDelay(2, baseMs, capMs));
        assertEquals(1000, halfPolicy.calculateFullJitterDelay(3, baseMs, capMs));
        assertEquals(4000, halfPolicy.calculateFullJitterDelay(5, baseMs, capMs));
    }

    @Test
    @DisplayName("Large attempt numbers do not cause integer overflow and respect cap")
    void testOverflowSafety() {
        RetryJitterPolicy maxPolicy = new RetryJitterPolicy(() -> 1.0);
        long baseMs = 500;
        long capMs = 8000;

        assertEquals(8000, maxPolicy.calculateFullJitterDelay(35, baseMs, capMs));
        assertEquals(8000, maxPolicy.calculateFullJitterDelay(64, baseMs, capMs));
        assertEquals(8000, maxPolicy.calculateFullJitterDelay(100, baseMs, capMs));
    }

    @Test
    @DisplayName("Zero or negative parameters return 0 delay safely")
    void testSafeZeroHandling() {
        RetryJitterPolicy policy = new RetryJitterPolicy(() -> 0.5);
        assertEquals(0, policy.calculateFullJitterDelay(1, 0, 8000));
        assertEquals(0, policy.calculateFullJitterDelay(1, 500, 0));
        assertEquals(0, policy.calculateFullJitterDelay(1, -500, 8000));
    }
}
