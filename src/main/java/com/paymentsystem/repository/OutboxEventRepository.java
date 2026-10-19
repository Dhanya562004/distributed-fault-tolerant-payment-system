package com.paymentsystem.repository;

import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByEventId(String eventId);

    List<OutboxEvent> findByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateId(String aggregateId);

    /**
     * Finds candidates eligible for processing:
     * 1. Status PENDING and nextAttemptAt <= now.
     * 2. Status PROCESSING and lease expired (lockedUntil < now) due to worker crash.
     */
    @Query("SELECT o.id FROM OutboxEvent o WHERE (o.status = com.paymentsystem.entity.OutboxStatus.PENDING AND o.nextAttemptAt <= :now) " +
           "OR (o.status = com.paymentsystem.entity.OutboxStatus.PROCESSING AND o.lockedUntil < :now) " +
           "ORDER BY o.nextAttemptAt ASC")
    List<Long> findCandidateEventIds(@Param("now") Instant now, Pageable pageable);

    /**
     * Atomically claims an event by ID if it is currently PENDING or if its lease has expired.
     * Guarantees mutual exclusion across distributed worker instances without holding long database transactions.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.status = :newStatus, o.leaseOwner = :workerId, o.lockedUntil = :lockedUntil, o.updatedAt = :now " +
           "WHERE o.id = :id AND (o.status = com.paymentsystem.entity.OutboxStatus.PENDING OR (o.status = com.paymentsystem.entity.OutboxStatus.PROCESSING AND o.lockedUntil < :now))")
    int claimEvent(@Param("id") Long id,
                   @Param("workerId") String workerId,
                   @Param("lockedUntil") Instant lockedUntil,
                   @Param("now") Instant now,
                   @Param("newStatus") OutboxStatus newStatus);

    /**
     * Atomically queues a DEAD_LETTER event for manual redelivery with its original eventId preserved.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.status = com.paymentsystem.entity.OutboxStatus.PENDING, o.attemptCount = 0, " +
           "o.nextAttemptAt = :now, o.lockedUntil = null, o.leaseOwner = null, o.lastError = null, o.updatedAt = :now " +
           "WHERE o.eventId = :eventId AND o.status = com.paymentsystem.entity.OutboxStatus.DEAD_LETTER")
    int resetDeadLetterEvent(@Param("eventId") String eventId, @Param("now") Instant now);
}
