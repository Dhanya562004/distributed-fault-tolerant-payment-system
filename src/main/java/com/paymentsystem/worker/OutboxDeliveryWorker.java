package com.paymentsystem.worker;

import com.paymentsystem.dto.DeliveryResult;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.OutboxStatus;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.service.MetricsService;
import com.paymentsystem.service.WebhookDeliveryService;
import com.paymentsystem.service.WebhookRetryPolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Scheduled background delivery worker for Transactional Outbox events.
 *
 * Implements:
 * - Distributed mutual exclusion via atomic conditional updates (claimEvent)
 * - Lease expiration recovery for worker crash resilience
 * - Zero open DB transactions during outbound HTTP calls
 * - Bounded batch claiming and bounded execution thread limits
 * - Exponential backoff with random jitter on retryable failures
 * - Terminal transition to DEAD_LETTER on retry exhaustion or permanent client error
 */
@Component
public class OutboxDeliveryWorker {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDeliveryWorker.class);

    private final OutboxEventRepository outboxEventRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final MetricsService metricsService;
    private final Clock clock;
    private final String workerId;
    private final ExecutorService workerExecutor;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    @Value("${app.webhook.enabled:false}")
    private boolean enabled = false;

    @Value("${app.webhook.batch-size:50}")
    private int batchSize = 50;

    @Value("${app.webhook.lease-duration-seconds:60}")
    private long leaseDurationSeconds = 60;

    @Value("${app.webhook.base-delay-ms:1000}")
    private long baseDelayMs = 1000;

    @Value("${app.webhook.max-delay-ms:300000}")
    private long maxDelayMs = 300000;

    private volatile boolean shuttingDown = false;

    @Autowired
    public OutboxDeliveryWorker(
            OutboxEventRepository outboxEventRepository,
            WebhookDeliveryService webhookDeliveryService,
            MetricsService metricsService,
            @Autowired(required = false) org.springframework.transaction.PlatformTransactionManager transactionManager,
            Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.webhookDeliveryService = webhookDeliveryService;
        this.metricsService = metricsService;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.txTemplate = transactionManager != null ? new org.springframework.transaction.support.TransactionTemplate(transactionManager) : null;
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.workerExecutor = new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "outbox-worker-" + (++count));
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public OutboxDeliveryWorker(
            OutboxEventRepository outboxEventRepository,
            WebhookDeliveryService webhookDeliveryService,
            MetricsService metricsService,
            Clock clock) {
        this(outboxEventRepository, webhookDeliveryService, metricsService, null, clock);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setLeaseDurationSeconds(long leaseDurationSeconds) {
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    public void setBaseDelayMs(long baseDelayMs) {
        this.baseDelayMs = baseDelayMs;
    }

    public void setMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    /**
     * Scheduled polling loop to claim and dispatch pending outbox events.
     * Returns count of events claimed and processed during this iteration.
     */
    @Scheduled(fixedDelayString = "${app.webhook.poll-interval-ms:5000}")
    public int processPendingEvents() {
        if (!enabled || shuttingDown) {
            return 0;
        }

        Instant now = Instant.now(clock);
        Instant lockedUntil = now.plusSeconds(leaseDurationSeconds);
        Pageable pageable = PageRequest.of(0, Math.max(1, batchSize));

        List<Long> candidateIds = outboxEventRepository.findCandidateEventIds(now, pageable);
        if (candidateIds.isEmpty()) {
            return 0;
        }

        logger.debug("[OUTBOX POLL] Found {} candidate outbox event(s) for claiming", candidateIds.size());
        int processedCount = 0;

        for (Long eventId : candidateIds) {
            if (shuttingDown) break;

            boolean claimed = claimAndDispatch(eventId, now, lockedUntil);
            if (claimed) {
                processedCount++;
            }
        }

        return processedCount;
    }

    /**
     * Atomically claims an event in a micro-transaction, performs HTTP dispatch outside
     * any database transaction, and records the delivery result in a separate micro-transaction.
     */
    public boolean claimAndDispatch(Long id, Instant now, Instant lockedUntil) {
        Integer claimed = (txTemplate != null)
                ? txTemplate.execute(status -> outboxEventRepository.claimEvent(id, workerId, lockedUntil, now, OutboxStatus.PROCESSING))
                : outboxEventRepository.claimEvent(id, workerId, lockedUntil, now, OutboxStatus.PROCESSING);

        if (claimed == null || claimed == 0) {
            // Another worker won the claim race or event status changed
            return false;
        }

        OutboxEvent event = (txTemplate != null)
                ? txTemplate.execute(status -> outboxEventRepository.findById(id).orElse(null))
                : outboxEventRepository.findById(id).orElse(null);

        if (event == null) {
            return false;
        }

        if (event.getLeaseOwner() == null) {
            event.setLeaseOwner(workerId);
        }

        logger.info("[OUTBOX CLAIM] Worker {} claimed event {} (status: PROCESSING, lease until: {})",
                workerId, event.getEventId(), lockedUntil);

        // Crucial Architectural Rule: Outbound HTTP dispatch MUST occur outside any open DB transaction
        DeliveryResult result = webhookDeliveryService.deliver(event);

        // Record terminal or retry state in short transaction
        recordDeliveryResult(event.getId(), result);
        return true;
    }

    /**
     * Updates outbox event state post-delivery in an independent short transaction.
     */
    public void recordDeliveryResult(Long eventTableId, DeliveryResult result) {
        Runnable updateAction = () -> {
            OutboxEvent event = outboxEventRepository.findById(eventTableId).orElse(null);
            if (event == null) {
                return;
            }

            // Guard against stale worker overwrite after lease loss:
            // 1. If event is already DELIVERED by another worker, do not overwrite terminal success.
            if (event.getStatus() == OutboxStatus.DELIVERED) {
                logger.info("[OUTBOX LEASE] Event {} is already marked DELIVERED by another worker, skipping duplicate update", event.getEventId());
                return;
            }

            // 2. If lease owner has changed to another worker, this worker has lost its lease; discard stale result.
            if (event.getLeaseOwner() != null && !workerId.equals(event.getLeaseOwner())) {
                logger.warn("[OUTBOX STALE WORKER] Worker {} lost lease for event {} (current lease owner: {}). Discarding stale delivery result.",
                        workerId, event.getEventId(), event.getLeaseOwner());
                return;
            }

            Instant now = Instant.now(clock);
            event.setUpdatedAt(now);
            event.setLockedUntil(null);
            event.setLeaseOwner(null);

            if (result.isSuccess()) {
                event.setStatus(OutboxStatus.DELIVERED);
                event.setDeliveredAt(now);
                event.setLastError(null);
                outboxEventRepository.save(event);
                if (metricsService != null) {
                    metricsService.incrementWebhookSuccess();
                }
                logger.info("[OUTBOX DELIVERED] Event {} successfully delivered and marked DELIVERED", event.getEventId());

            } else if (result.isRetryable()) {
                int newAttemptCount = event.getAttemptCount() + 1;
                event.setAttemptCount(newAttemptCount);
                event.setLastError(result.getErrorMessage());

                if (newAttemptCount >= event.getMaxAttempts()) {
                    event.setStatus(OutboxStatus.DEAD_LETTER);
                    outboxEventRepository.save(event);
                    if (metricsService != null) {
                        metricsService.incrementWebhookDlq();
                    }
                    logger.error("[OUTBOX EXHAUSTED] Event {} exceeded max attempts ({}/{}). Transitioned to DEAD_LETTER: {}",
                            event.getEventId(), newAttemptCount, event.getMaxAttempts(), result.getErrorMessage());
                } else {
                    event.setStatus(OutboxStatus.PENDING);
                    long backoffMs = WebhookRetryPolicy.calculateBackoff(newAttemptCount, baseDelayMs, maxDelayMs);
                    event.setNextAttemptAt(now.plusMillis(backoffMs));
                    outboxEventRepository.save(event);
                    if (metricsService != null) {
                        metricsService.incrementWebhookFailure();
                    }
                    logger.warn("[OUTBOX RETRY SCHEDULED] Event {} retry {}/{} scheduled at {} (+{}ms): {}",
                            event.getEventId(), newAttemptCount, event.getMaxAttempts(), event.getNextAttemptAt(), backoffMs, result.getErrorMessage());
                }

            } else {
                // Permanent failure (e.g. 400 Bad Request, SSRF blocked, URL error)
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setStatus(OutboxStatus.DEAD_LETTER);
                event.setLastError(result.getErrorMessage());
                outboxEventRepository.save(event);
                if (metricsService != null) {
                    metricsService.incrementWebhookDlq();
                }
                logger.warn("[OUTBOX PERMANENT FAILURE] Event {} failed with permanent error. Transitioned to DEAD_LETTER: {}",
                        event.getEventId(), result.getErrorMessage());
            }
        };

        if (txTemplate != null) {
            txTemplate.executeWithoutResult(status -> updateAction.run());
        } else {
            updateAction.run();
        }
    }

    @PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
        logger.info("Gracefully shutting down OutboxDeliveryWorker pool...");
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
