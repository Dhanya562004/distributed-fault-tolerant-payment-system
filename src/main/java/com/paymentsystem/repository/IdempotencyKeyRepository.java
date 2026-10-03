package com.paymentsystem.repository;

import com.paymentsystem.entity.IdempotencyKeyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, Long> {

    Optional<IdempotencyKeyRecord> findByKeyValue(String keyValue);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM IdempotencyKeyRecord i WHERE i.keyValue = :keyValue")
    Optional<IdempotencyKeyRecord> findByKeyValueForUpdate(@Param("keyValue") String keyValue);
}
