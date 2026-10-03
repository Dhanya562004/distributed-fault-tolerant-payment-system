package com.paymentsystem.worker.impl;

import com.paymentsystem.worker.DistributedQueueManager;
import com.paymentsystem.worker.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class DistributedQueueManagerImpl implements DistributedQueueManager {

    private static final Logger logger = LoggerFactory.getLogger(DistributedQueueManagerImpl.class);
    private static final int NUM_PARTITIONS = 4;

    private final List<BlockingQueue<EventMessage>> partitions = new ArrayList<>();
    private final BlockingQueue<EventMessage> deadLetterQueue = new LinkedBlockingQueue<>();
    private final Map<String, String> dlqReasons = new ConcurrentHashMap<>();

    public DistributedQueueManagerImpl() {
        for (int i = 0; i < NUM_PARTITIONS; i++) {
            partitions.add(new LinkedBlockingQueue<>(1000));
        }
    }

    @Override
    public int getPartitionCount() {
        return NUM_PARTITIONS;
    }

    @Override
    public int calculatePartition(String partitionKey) {
        if (partitionKey == null) return 0;
        return Math.abs(partitionKey.hashCode()) % NUM_PARTITIONS;
    }

    @Override
    public boolean publish(String partitionKey, EventMessage message) {
        int partitionIdx = calculatePartition(partitionKey);
        boolean offered = partitions.get(partitionIdx).offer(message);
        if (offered) {
            logger.info("Published message [Event: {}, Payment: {}] to Partition {}", message.getEventId(), message.getPaymentId(), partitionIdx);
        } else {
            logger.error("Partition {} queue is FULL! Could not publish payment: {}", partitionIdx, message.getPaymentId());
        }
        return offered;
    }

    @Override
    public EventMessage pollPartition(int partitionIdx) {
        return partitions.get(partitionIdx).poll();
    }

    @Override
    public void sendToDeadLetterQueue(EventMessage message, String failureReason) {
        logger.error("[DLQ ROUTING] Moving Payment {} to Dead Letter Queue after max retries. Reason: {}", message.getPaymentId(), failureReason);
        deadLetterQueue.offer(message);
        if (message.getPaymentId() != null && failureReason != null) {
            dlqReasons.put(message.getPaymentId(), failureReason);
        }
    }

    @Override
    public long getTotalQueueDepth() {
        long depth = 0;
        for (BlockingQueue<EventMessage> p : partitions) {
            depth += p.size();
        }
        return depth;
    }

    @Override
    public long getDlqCount() {
        return deadLetterQueue.size();
    }

    @Override
    public List<EventMessage> getDlqMessages() {
        return new ArrayList<>(deadLetterQueue);
    }

    @Override
    public String getDlqReason(String paymentId) {
        return dlqReasons.getOrDefault(paymentId, "Max retry attempts exhausted");
    }
}
