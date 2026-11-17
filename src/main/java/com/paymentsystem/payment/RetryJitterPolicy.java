package com.paymentsystem.payment;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Encapsulates Full Jitter exponential backoff calculation:
 * Capped backoff = min(maxBackoffMs, baseBackoffMs * 2^(attempt - 1))
 * Delay = random(0, Capped backoff)
 *
 * Random source is injectable via constructor to prevent flaky tests.
 */
@Component
public class RetryJitterPolicy {

    private final DoubleSupplier randomSupplier;

    public RetryJitterPolicy() {
        this(() -> ThreadLocalRandom.current().nextDouble());
    }

    public RetryJitterPolicy(DoubleSupplier randomSupplier) {
        this.randomSupplier = randomSupplier != null ? randomSupplier : () -> ThreadLocalRandom.current().nextDouble();
    }

    /**
     * Calculates Full Jitter backoff delay for the given attempt.
     *
     * @param attempt 1-based attempt number (1, 2, 3...)
     * @param baseBackoffMs base delay in milliseconds (e.g., 500)
     * @param maxBackoffMs maximum delay cap in milliseconds (e.g., 8000)
     * @return random delay in milliseconds, guaranteed to be in [0, maxBackoffMs]
     */
    public long calculateFullJitterDelay(int attempt, long baseBackoffMs, long maxBackoffMs) {
        if (baseBackoffMs <= 0 || maxBackoffMs <= 0) {
            return 0;
        }
        long safeBase = Math.min(baseBackoffMs, maxBackoffMs);
        int safeAttempt = Math.max(1, attempt);

        long exponentialCap;
        if (safeAttempt > 30) {
            exponentialCap = maxBackoffMs;
        } else {
            long multiplier = 1L << (safeAttempt - 1);
            if (safeBase > maxBackoffMs / multiplier) {
                exponentialCap = maxBackoffMs;
            } else {
                exponentialCap = Math.min(maxBackoffMs, safeBase * multiplier);
            }
        }

        double rand = randomSupplier.getAsDouble();
        double clampedRand = Math.max(0.0, Math.min(1.0, rand));
        return (long) (clampedRand * exponentialCap);
    }
}
