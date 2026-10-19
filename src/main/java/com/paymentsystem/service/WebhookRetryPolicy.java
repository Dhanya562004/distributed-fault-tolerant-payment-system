package com.paymentsystem.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Encapsulates retry classification, exponential backoff calculation,
 * and random jitter logic for webhook delivery attempts.
 */
public final class WebhookRetryPolicy {

    private WebhookRetryPolicy() {
    }

    /**
     * Calculates exponential backoff with random jitter:
     * baseDelay * 2^(attempt - 1) + jitter, capped at maxDelay.
     *
     * @param attempt 1-indexed attempt number (1, 2, 3...)
     * @param baseDelayMs initial delay for first retry
     * @param maxDelayMs maximum allowable delay
     * @return delay in milliseconds
     */
    public static long calculateBackoff(int attempt, long baseDelayMs, long maxDelayMs) {
        double jitterFraction = ThreadLocalRandom.current().nextDouble(0.0, 0.25);
        return calculateBackoff(attempt, baseDelayMs, maxDelayMs, jitterFraction);
    }

    /**
     * Deterministic variant of calculateBackoff for unit tests.
     */
    public static long calculateBackoff(int attempt, long baseDelayMs, long maxDelayMs, double jitterFraction) {
        if (baseDelayMs <= 0) {
            return 0;
        }

        int effectiveAttempt = Math.max(1, attempt);
        int shift = Math.min(effectiveAttempt - 1, 30);
        long multiplier = 1L << shift;

        long unjitteredDelay;
        if (multiplier > Long.MAX_VALUE / baseDelayMs) {
            unjitteredDelay = maxDelayMs;
        } else {
            unjitteredDelay = Math.min(maxDelayMs, baseDelayMs * multiplier);
        }

        double clampedJitterFraction = Math.max(0.0, Math.min(1.0, jitterFraction));
        long jitter = (long) (unjitteredDelay * clampedJitterFraction);

        return Math.min(maxDelayMs, unjitteredDelay + jitter);
    }

    /**
     * Returns true if the HTTP response status code indicates a transient, retryable failure.
     */
    public static boolean isRetryableStatusCode(int statusCode) {
        if (statusCode == 429) {
            return true; // Rate limiting is retryable
        }
        // All 5xx server errors (500 Internal Error, 502 Bad Gateway, 503 Unavailable, 504 Gateway Timeout) are retryable
        return statusCode >= 500 && statusCode <= 599;
    }

    /**
     * Returns true if the exception represents a transient network or timeout condition.
     */
    public static boolean isRetryableException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        if (throwable instanceof SocketTimeoutException ||
            throwable instanceof ConnectException ||
            throwable.getClass().getName().contains("HttpTimeoutException") ||
            throwable.getClass().getName().contains("HttpConnectTimeoutException")) {
            return true;
        }

        if (throwable instanceof SecurityException || throwable instanceof IllegalArgumentException) {
            return false; // Security or configuration errors are permanent
        }

        return throwable instanceof IOException;
    }
}
