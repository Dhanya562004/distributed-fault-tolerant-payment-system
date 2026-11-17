package com.paymentsystem.worker;
import com.paymentsystem.outbox.DistributedQueueManager;

import com.paymentsystem.outbox.DistributedQueueManagerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DistributedQueueManagerTest {

    private DistributedQueueManager queueManager;

    @BeforeEach
    void setUp() {
        queueManager = new DistributedQueueManagerImpl();
    }

    @Test
    @DisplayName("calculatePartition should handle Integer.MIN_VALUE hashCode without negative index")
    void testCalculatePartition_IntegerMinValue() {
        String keyWithMinValueHash = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, keyWithMinValueHash.hashCode(),
                "Sanity check: polygenelubricants must have hashCode Integer.MIN_VALUE");

        int partition = queueManager.calculatePartition(keyWithMinValueHash);
        assertTrue(partition >= 0 && partition < queueManager.getPartitionCount(),
                "Partition must be non-negative and < partitionCount, but was: " + partition);
        assertEquals(0, partition);
    }

    @Test
    @DisplayName("calculatePartition should handle negative hash codes safely using floorMod")
    void testCalculatePartition_NegativeHashCode() {
        String keyWithNegativeHash = "user_123";
        assertTrue(keyWithNegativeHash.hashCode() < 0,
                "Sanity check: user_123 must have negative hashCode");

        int partition = queueManager.calculatePartition(keyWithNegativeHash);
        assertTrue(partition >= 0 && partition < queueManager.getPartitionCount(),
                "Partition must be non-negative and < partitionCount, but was: " + partition);
    }

    @Test
    @DisplayName("calculatePartition should return 0 for null key")
    void testCalculatePartition_NullKey() {
        int partition = queueManager.calculatePartition(null);
        assertEquals(0, partition);
    }

    @Test
    @DisplayName("calculatePartition should handle standard positive hash codes")
    void testCalculatePartition_PositiveHashCode() {
        String key = "USR_ALICE";
        int partition = queueManager.calculatePartition(key);
        assertTrue(partition >= 0 && partition < queueManager.getPartitionCount(),
                "Partition must be non-negative and < partitionCount, but was: " + partition);
    }
}
