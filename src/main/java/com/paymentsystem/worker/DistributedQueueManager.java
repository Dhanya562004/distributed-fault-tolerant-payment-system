package com.paymentsystem.worker;

import java.util.List;

public interface DistributedQueueManager {
    int getPartitionCount();
    int calculatePartition(String partitionKey);
    boolean publish(String partitionKey, EventMessage message);
    EventMessage pollPartition(int partitionIdx);
    void sendToDeadLetterQueue(EventMessage message, String failureReason);
    long getTotalQueueDepth();
    long getDlqCount();
    List<EventMessage> getDlqMessages();
    String getDlqReason(String paymentId);
}
