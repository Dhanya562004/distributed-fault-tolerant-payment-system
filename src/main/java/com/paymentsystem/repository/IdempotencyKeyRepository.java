package com.paymentsystem.repository;

import com.paymentsystem.entity.IdempotencyKeyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, Long> {

    Optional<IdempotencyKeyRecord> findByKeyValue(String keyValue);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM IdempotencyKeyRecord i WHERE i.keyValue = :keyValue")
    Optional<IdempotencyKeyRecord> findByKeyValueForUpdate(@Param("keyValue") String keyValue);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyKeyRecord i SET i.status = :newStatus, i.requestHash = :requestHash, i.responseBody = NULL, i.httpStatusCode = NULL, i.createdAt = :now, i.updatedAt = :now, i.expiresAt = :newExpiresAt WHERE i.keyValue = :keyValue AND i.expiresAt <= :now")
    int updateIfExpired(@Param("keyValue") String keyValue,
                        @Param("requestHash") String requestHash,
                        @Param("now") Instant now,
                        @Param("newExpiresAt") Instant newExpiresAt,
                        @Param("newStatus") IdempotencyKeyRecord.Status newStatus);

    @Modifying
    @Query("DELETE FROM IdempotencyKeyRecord i WHERE i.expiresAt <= :now AND i.status != :excludedStatus")
    int deleteExpiredKeysExcludingStatus(@Param("now") Instant now, @Param("excludedStatus") IdempotencyKeyRecord.Status excludedStatus);

    @Query("SELECT COUNT(i) FROM IdempotencyKeyRecord i WHERE i.expiresAt <= :now")
    long countExpiredKeys(@Param("now") Instant now);
}
