package com.paymentsystem.test;

import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;

import java.util.UUID;

/**
 * Test helper for constructing PaymentTransaction entities in specific states
 * by strictly walking allowed state transitions (never bypassing transition rules).
 */
public final class PaymentTestHelper {

    private PaymentTestHelper() {
    }

    public static PaymentTransaction createInState(PaymentStatus targetStatus) {
        String paymentId = "PAY_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String idemKey = "IDEM_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return createInState(paymentId, idemKey, "USR_BOB", 10000L, "USD", targetStatus);
    }

    public static PaymentTransaction createInState(String paymentId, String idempotencyKey, String userId,
                                                  Long amount, String currency, PaymentStatus targetStatus) {
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, idempotencyKey, userId, amount, currency, "CREDIT_CARD", "Test payment"
        );
        walkToState(tx, targetStatus);
        return tx;
    }

    public static void walkToState(PaymentTransaction tx, PaymentStatus targetStatus) {
        if (tx.getStatus() == targetStatus) {
            return;
        }

        switch (targetStatus) {
            case PENDING:
                // Already created in PENDING by default
                break;
            case PROCESSING:
                if (tx.getStatus() == PaymentStatus.PENDING) {
                    tx.transitionTo(PaymentStatus.PROCESSING);
                }
                break;
            case SUCCESS:
                if (tx.getStatus() == PaymentStatus.PENDING) {
                    tx.transitionTo(PaymentStatus.PROCESSING);
                }
                if (tx.getStatus() == PaymentStatus.PROCESSING) {
                    tx.transitionTo(PaymentStatus.SUCCESS);
                }
                break;
            case REFUNDED:
                if (tx.getStatus() == PaymentStatus.PENDING) {
                    tx.transitionTo(PaymentStatus.PROCESSING);
                }
                if (tx.getStatus() == PaymentStatus.PROCESSING) {
                    tx.transitionTo(PaymentStatus.SUCCESS);
                }
                if (tx.getStatus() == PaymentStatus.SUCCESS) {
                    tx.transitionTo(PaymentStatus.REFUNDED);
                }
                break;
            case FAILED:
                if (tx.getStatus() == PaymentStatus.PENDING || tx.getStatus() == PaymentStatus.PROCESSING) {
                    tx.transitionTo(PaymentStatus.FAILED);
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported test target status: " + targetStatus);
        }
    }
}
