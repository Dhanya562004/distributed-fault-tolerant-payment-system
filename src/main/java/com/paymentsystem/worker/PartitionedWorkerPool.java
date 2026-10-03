package com.paymentsystem.worker;

import com.paymentsystem.service.PaymentProcessingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class PartitionedWorkerPool {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedWorkerPool.class);
    private static final int MAX_WORKER_RETRIES = 3;

    private final DistributedQueueManager queueManager;
    private final PaymentProcessingService paymentProcessingService;
    private final List<ExecutorService> partitionExecutors = new ArrayList<>();
    private volatile boolean running = true;

    @Autowired
    public PartitionedWorkerPool(DistributedQueueManager queueManager, PaymentProcessingService paymentProcessingService) {
        this.queueManager = queueManager;
        this.paymentProcessingService = paymentProcessingService;
    }

    @PostConstruct
    public void startWorkers() {
        int partitionCount = queueManager.getPartitionCount();
        logger.info("Initializing Partitioned Worker Pool with {} partition consumers...", partitionCount);

        for (int i = 0; i < partitionCount; i++) {
            final int partitionIdx = i;
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "partition-worker-" + partitionIdx));
            partitionExecutors.add(executor);

            executor.submit(() -> runPartitionConsumerLoop(partitionIdx));
        }
    }

    private void runPartitionConsumerLoop(int partitionIdx) {
        String workerId = "WORKER_P" + partitionIdx;
        logger.info("Started consumer loop for partition {}", partitionIdx);

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                EventMessage message = queueManager.pollPartition(partitionIdx);
                if (message != null) {
                    processMessageWithRetry(message, workerId);
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in consumer loop for partition {}: {}", partitionIdx, e.getMessage(), e);
            }
        }
        logger.info("Stopped consumer loop for partition {}", partitionIdx);
    }

    private void processMessageWithRetry(EventMessage message, String workerId) {
        int currentAttempt = message.getAttemptCount() + 1;
        message.setAttemptCount(currentAttempt);

        boolean success = paymentProcessingService.processWorkerMessage(message, workerId);

        if (!success) {
            if (currentAttempt < MAX_WORKER_RETRIES) {
                long backoffMs = (long) (Math.pow(2, currentAttempt) * 500); // 1s, 2s, 4s backoff
                message.setNextRetryTimestamp(System.currentTimeMillis() + backoffMs);
                logger.warn("[WORKER RETRY {}/{}] Re-enqueuing payment {} with {}ms delay", currentAttempt, MAX_WORKER_RETRIES, message.getPaymentId(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                queueManager.publish(message.getUserId(), message);
            } else {
                queueManager.sendToDeadLetterQueue(message, "Exhausted max worker retry attempts (" + MAX_WORKER_RETRIES + ")");
            }
        }
    }

    @PreDestroy
    public void stopWorkers() {
        this.running = false;
        logger.info("Shutting down Partitioned Worker Pool...");
        for (ExecutorService executor : partitionExecutors) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }
}
