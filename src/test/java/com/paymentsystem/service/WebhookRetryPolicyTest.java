package com.paymentsystem.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class WebhookRetryPolicyTest {

    private final long baseDelayMs = 1000;
    private final long maxDelayMs = 300000; // 5 minutes

    @Test
    @DisplayName("Exponential backoff increases exponentially across retry attempts")
    void testCalculateBackoff_IncreasesCorrectly() {
        // Without jitter (jitter fraction = 0)
        long delay1 = WebhookRetryPolicy.calculateBackoff(1, baseDelayMs, maxDelayMs, 0.0);
        long delay2 = WebhookRetryPolicy.calculateBackoff(2, baseDelayMs, maxDelayMs, 0.0);
        long delay3 = WebhookRetryPolicy.calculateBackoff(3, baseDelayMs, maxDelayMs, 0.0);
        long delay4 = WebhookRetryPolicy.calculateBackoff(4, baseDelayMs, maxDelayMs, 0.0);
        long delay5 = WebhookRetryPolicy.calculateBackoff(5, baseDelayMs, maxDelayMs, 0.0);

        assertEquals(1000, delay1, "Attempt 1 should have base delay of 1000ms");
        assertEquals(2000, delay2, "Attempt 2 should be 2000ms");
        assertEquals(4000, delay3, "Attempt 3 should be 4000ms");
        assertEquals(8000, delay4, "Attempt 4 should be 8000ms");
        assertEquals(16000, delay5, "Attempt 5 should be 16000ms");
    }

    @Test
    @DisplayName("Jitter stays strictly within expected 0% to 25% bounds")
    void testCalculateBackoff_JitterBounds() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            long minDelay = WebhookRetryPolicy.calculateBackoff(attempt, baseDelayMs, maxDelayMs, 0.0);
            long maxJitterDelay = WebhookRetryPolicy.calculateBackoff(attempt, baseDelayMs, maxDelayMs, 0.25);

            for (int i = 0; i < 50; i++) {
                long randomDelay = WebhookRetryPolicy.calculateBackoff(attempt, baseDelayMs, maxDelayMs);
                assertTrue(randomDelay >= minDelay, "Random delay must be >= unjittered base: " + randomDelay + " >= " + minDelay);
                assertTrue(randomDelay <= maxJitterDelay, "Random delay must be <= 1.25x unjittered: " + randomDelay + " <= " + maxJitterDelay);
            }
        }
    }

    @Test
    @DisplayName("Exponential backoff respects maximum configured delay cap")
    void testCalculateBackoff_MaxDelayCap() {
        long delayAttempt10 = WebhookRetryPolicy.calculateBackoff(10, baseDelayMs, maxDelayMs, 0.0);
        assertEquals(maxDelayMs, delayAttempt10, "Delays above maxDelay must be capped at maxDelayMs");

        long delayAttempt20 = WebhookRetryPolicy.calculateBackoff(20, baseDelayMs, maxDelayMs);
        assertEquals(maxDelayMs, delayAttempt20, "High attempts must not exceed maxDelayMs");
    }

    @Test
    @DisplayName("Large attempt numbers do not cause integer overflow")
    void testCalculateBackoff_IntegerOverflowSafety() {
        assertDoesNotThrow(() -> {
            long delayAttempt50 = WebhookRetryPolicy.calculateBackoff(50, baseDelayMs, maxDelayMs);
            assertEquals(maxDelayMs, delayAttempt50);

            long delayAttempt100 = WebhookRetryPolicy.calculateBackoff(100, baseDelayMs, maxDelayMs);
            assertEquals(maxDelayMs, delayAttempt100);
        });
    }

    @Test
    @DisplayName("Retryable HTTP status codes are correctly classified")
    void testIsRetryableStatusCode() {
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(429), "HTTP 429 Too Many Requests must be retryable");
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(500), "HTTP 500 must be retryable");
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(502), "HTTP 502 Bad Gateway must be retryable");
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(503), "HTTP 503 Service Unavailable must be retryable");
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(504), "HTTP 504 Gateway Timeout must be retryable");

        // Non-retryable permanent client errors
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(400), "HTTP 400 Bad Request is permanent");
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(401), "HTTP 401 Unauthorized is permanent");
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(403), "HTTP 403 Forbidden is permanent");
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(404), "HTTP 404 Not Found is permanent");
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(422), "HTTP 422 Unprocessable is permanent");

        // Success responses
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(200), "HTTP 200 OK is success, not retryable");
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(204), "HTTP 204 No Content is success, not retryable");
    }

    @Test
    @DisplayName("Retryable and non-retryable exceptions are correctly classified")
    void testIsRetryableException() {
        assertTrue(WebhookRetryPolicy.isRetryableException(new SocketTimeoutException("Read timed out")));
        assertTrue(WebhookRetryPolicy.isRetryableException(new ConnectException("Connection refused")));
        assertTrue(WebhookRetryPolicy.isRetryableException(new IOException("Broken pipe")));

        assertFalse(WebhookRetryPolicy.isRetryableException(new SecurityException("SSRF blocked")));
        assertFalse(WebhookRetryPolicy.isRetryableException(new IllegalArgumentException("Invalid URL")));
        assertFalse(WebhookRetryPolicy.isRetryableException(null));
    }
}
