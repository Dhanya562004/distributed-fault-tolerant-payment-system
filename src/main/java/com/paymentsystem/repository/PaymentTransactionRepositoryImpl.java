package com.paymentsystem.repository;

import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of custom keyset pagination queries for PaymentTransaction.
 * Avoids SQL OFFSET entirely and ensures index-backed row retrieval.
 */
@Repository
public class PaymentTransactionRepositoryImpl implements PaymentTransactionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    public PaymentTransactionRepositoryImpl() {
    }

    public PaymentTransactionRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<PaymentTransaction> findTransactionsKeyset(
            PaymentStatus status,
            Instant fromDate,
            Instant toDate,
            Instant cursorCreatedAt,
            Long cursorId,
            int limit) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<PaymentTransaction> cq = cb.createQuery(PaymentTransaction.class);
        Root<PaymentTransaction> root = cq.from(PaymentTransaction.class);

        List<Predicate> predicates = new ArrayList<>();

        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }

        if (fromDate != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
        }

        if (toDate != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate));
        }

        if (cursorCreatedAt != null && cursorId != null) {
            Predicate olderTime = cb.lessThan(root.get("createdAt"), cursorCreatedAt);
            Predicate sameTime = cb.equal(root.get("createdAt"), cursorCreatedAt);
            Predicate smallerId = cb.lessThan(root.get("id"), cursorId);
            Predicate tieBreaker = cb.and(sameTime, smallerId);
            predicates.add(cb.or(olderTime, tieBreaker));
        }

        if (!predicates.isEmpty()) {
            cq.where(cb.and(predicates.toArray(new Predicate[0])));
        }

        cq.orderBy(
                cb.desc(root.get("createdAt")),
                cb.desc(root.get("id"))
        );

        return entityManager.createQuery(cq)
                .setMaxResults(limit)
                .getResultList();
    }
}
