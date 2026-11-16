package com.paymentsystem.repository;

import com.paymentsystem.entity.RefundTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundTransactionRepository extends JpaRepository<RefundTransaction, Long> {

    Optional<RefundTransaction> findByRefundId(String refundId);

    List<RefundTransaction> findByPaymentId(String paymentId);

    List<RefundTransaction> findByUserId(String userId);

    @Query("SELECT COALESCE(SUM(r.amount), 0L) FROM RefundTransaction r WHERE r.paymentId = :paymentId")
    Long sumRefundedByPaymentId(@Param("paymentId") String paymentId);
}
